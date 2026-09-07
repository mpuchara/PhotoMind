package com.photomind.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.photomind.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: PhotoRepository
    private lateinit var translator: QueryTranslator
    private lateinit var adapter: PhotoAdapter
    private var isIndexing = false
    private var startIndexAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasGalleryPermission()) {
            binding.statusText.text = "Dostęp do zdjęć przyznany."
            if (startIndexAfterPermission) startIndexing()
        } else {
            binding.statusText.text = "Bez dostępu do zdjęć PhotoMind nie może zbudować indeksu."
        }
        startIndexAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = PhotoRepository(this)
        translator = QueryTranslator()
        adapter = PhotoAdapter(::openPhoto, ::showTagDialog)

        binding.photoGrid.layoutManager = GridLayoutManager(this, 3)
        binding.photoGrid.adapter = adapter

        val existing = repository.indexedCount()
        binding.statusText.text = if (existing > 0) {
            "W indeksie: $existing zdjęć. Wpisz czego szukasz."
        } else {
            "Uruchom indeksowanie, aby PhotoMind nauczył się Twojej galerii."
        }

        binding.indexButton.setOnClickListener {
            if (isIndexing) {
                repository.cancelIndexing()
                binding.statusText.text = "Zatrzymuję indeksowanie…"
            } else {
                ensurePermissionAndIndex()
            }
        }

        binding.searchButton.setOnClickListener { performSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        if (existing > 0) performSearch()
    }

    private fun ensurePermissionAndIndex() {
        if (hasGalleryPermission()) {
            startIndexing()
        } else {
            startIndexAfterPermission = true
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun startIndexing() {
        if (isIndexing) return
        isIndexing = true
        binding.indexButton.text = "Zatrzymaj"
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true

        repository.indexAll(object : PhotoRepository.IndexCallback {
            override fun onStarted(total: Int) = runOnUiThread {
                binding.progress.isIndeterminate = false
                binding.progress.max = total.coerceAtLeast(1)
                binding.progress.progress = 0
                binding.statusText.text = "Indeksuję $total zdjęć lokalnie…"
            }

            override fun onProgress(done: Int, total: Int, indexed: Int, skipped: Int, failed: Int) = runOnUiThread {
                binding.progress.max = total.coerceAtLeast(1)
                binding.progress.progress = done
                binding.statusText.text = "$done / $total • nowe: $indexed • bez zmian: $skipped • pominięte: $failed"
            }

            override fun onFinished(summary: PhotoRepository.IndexSummary) = runOnUiThread {
                isIndexing = false
                binding.indexButton.text = getString(R.string.index_photos)
                binding.progress.visibility = View.GONE
                val count = repository.indexedCount()
                binding.statusText.text = if (summary.cancelled) {
                    "Indeksowanie zatrzymane. W indeksie: $count zdjęć."
                } else {
                    "Gotowe. W indeksie: $count zdjęć. Nowe: ${summary.indexed}, pominięte: ${summary.failed}."
                }
                performSearch()
            }
        })
    }

    private fun performSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (repository.indexedCount() == 0) {
            adapter.submit(emptyList())
            binding.statusText.text = "Indeks jest pusty — najpierw kliknij „Indeksuj zdjęcia”."
            return
        }

        if (query.isBlank()) {
            repository.search("", null) { results ->
                runOnUiThread {
                    adapter.submit(results)
                    binding.statusText.text = "Ostatnio zindeksowane: ${results.size} zdjęć."
                }
            }
            return
        }

        translator.translatePolishToEnglish(
            query = query,
            onPreparing = {
                runOnUiThread { binding.statusText.text = "Szukam lokalnie… Przy pierwszym użyciu pobieram model PL→EN." }
            },
            onResult = { translated ->
                repository.search(query, translated) { results ->
                    runOnUiThread {
                        adapter.submit(results)
                        binding.statusText.text = if (results.isEmpty()) {
                            "Brak wyników dla „$query”. Spróbuj krótszego opisu albo dodaj własny tag."
                        } else {
                            "Znaleziono ${results.size} zdjęć dla „$query”."
                        }
                    }
                }
            }
        )
    }

    private fun showTagDialog(photo: PhotoItem) {
        val input = EditText(this).apply {
            setText(photo.userTags)
            hint = "np. Maciek, Tadzio, działka, faktura"
            setPadding(48, 20, 48, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Dodaj imię lub własny tag")
            .setMessage("Długi opis nie jest potrzebny. Możesz wpisać kilka słów oddzielonych spacją lub przecinkiem.")
            .setView(input)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz") { _, _ ->
                repository.updateTags(photo, input.text.toString()) {
                    runOnUiThread {
                        adapter.itemChanged(photo)
                        Toast.makeText(this, "Tag zapisany lokalnie", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openPhoto(photo: PhotoItem) {
        val uri = Uri.parse(photo.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Nie udało się otworzyć zdjęcia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasGalleryPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        }
        Build.VERSION.SDK_INT >= 33 ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        translator.close()
        repository.close()
        super.onDestroy()
    }
}
