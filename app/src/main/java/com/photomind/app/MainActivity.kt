package com.photomind.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var destroyed = false
    private var searchGeneration = 0

    private enum class GalleryAccess { FULL, PARTIAL, NONE }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (destroyed) return@registerForActivityResult

        val access = galleryAccess()
        updateAccessUi()

        if (access != GalleryAccess.NONE) {
            binding.statusText.text = if (access == GalleryAccess.FULL) {
                "Pełny dostęp przyznany."
            } else {
                "Dostęp do wybranych zdjęć przyznany. Możesz później dodać kolejne."
            }
            if (startIndexAfterPermission) startIndexing()
        } else {
            binding.statusText.text = "Bez dostępu do zdjęć PhotoMind nie może zbudować indeksu."
            showPermissionHelp()
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
        updateAccessUi()

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

        if (existing > 0 && galleryAccess() != GalleryAccess.NONE) performSearch()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) updateAccessUi()
    }

    private fun ensurePermissionAndIndex() {
        when (galleryAccess()) {
            GalleryAccess.FULL -> startIndexing()
            GalleryAccess.PARTIAL -> showPartialAccessDialog()
            GalleryAccess.NONE -> requestGalleryAccessAndIndex()
        }
    }

    private fun showPartialAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Masz ograniczony dostęp")
            .setMessage(
                "PhotoMind widzi tylko zdjęcia wybrane w systemowym oknie Androida. " +
                    "Możesz dodać kolejne zdjęcia, zmienić wybór albo ponownie zaindeksować tylko obecnie udostępnione."
            )
            .setPositiveButton("Dodaj / zmień zdjęcia") { _, _ ->
                requestGalleryAccessAndIndex()
            }
            .setNeutralButton("Indeksuj wybrane") { _, _ ->
                startIndexing()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun requestGalleryAccessAndIndex() {
        startIndexAfterPermission = true
        permissionLauncher.launch(requiredPermissions())
    }

    private fun showPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("PhotoMind potrzebuje dostępu do zdjęć")
            .setMessage(
                "Możesz nadać dostęp do całej galerii albo tylko do wybranych zdjęć. " +
                    "Jeśli Android nie pokazuje już okna wyboru, zmień dostęp w ustawieniach aplikacji."
            )
            .setPositiveButton("Ustawienia aplikacji") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun updateAccessUi() {
        if (isIndexing || destroyed) return

        when (galleryAccess()) {
            GalleryAccess.FULL -> {
                binding.accessText.text = "Dostęp do galerii: pełny"
                binding.indexButton.text = "Odśwież indeks"
            }
            GalleryAccess.PARTIAL -> {
                binding.accessText.text = "Dostęp do galerii: ograniczony — tylko wybrane zdjęcia"
                binding.indexButton.text = "Dodaj / zmień zdjęcia"
            }
            GalleryAccess.NONE -> {
                binding.accessText.text = "Dostęp do galerii: brak"
                binding.indexButton.text = "Nadaj dostęp"
                adapter.submit(emptyList())
            }
        }
    }

    private fun startIndexing() {
        if (isIndexing || destroyed || galleryAccess() == GalleryAccess.NONE) return
        isIndexing = true
        binding.indexButton.text = "Zatrzymaj"
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true

        repository.indexAll(object : PhotoRepository.IndexCallback {
            override fun onStarted(total: Int) = runOnUiThread {
                if (destroyed) return@runOnUiThread
                binding.progress.isIndeterminate = false
                binding.progress.max = total.coerceAtLeast(1)
                binding.progress.progress = 0
                binding.statusText.text = if (total == 0) {
                    "Android nie udostępnia obecnie żadnych zdjęć. Dodaj zdjęcia do dostępu."
                } else {
                    "Indeksuję $total zdjęć lokalnie… Możesz w tym czasie wyszukiwać już zapisane zdjęcia."
                }
            }

            override fun onProgress(done: Int, total: Int, indexed: Int, skipped: Int, failed: Int) = runOnUiThread {
                if (destroyed) return@runOnUiThread
                binding.progress.max = total.coerceAtLeast(1)
                binding.progress.progress = done
                binding.statusText.text = "$done / $total • nowe: $indexed • bez zmian: $skipped • pominięte: $failed"
            }

            override fun onFinished(summary: PhotoRepository.IndexSummary) = runOnUiThread {
                if (destroyed) return@runOnUiThread
                isIndexing = false
                binding.progress.visibility = View.GONE
                updateAccessUi()
                val count = repository.indexedCount()

                binding.statusText.text = when {
                    summary.errorMessage != null -> "Błąd indeksowania: ${summary.errorMessage}"
                    summary.cancelled -> "Indeksowanie zatrzymane. Dostępnych w indeksie: $count zdjęć."
                    galleryAccess() == GalleryAccess.PARTIAL ->
                        "Gotowe. Dostępnych w indeksie: $count zdjęć. Masz ograniczony dostęp — użyj „Dodaj / zmień zdjęcia”, aby rozszerzyć wybór."
                    else ->
                        "Gotowe. W indeksie: $count zdjęć. Nowe: ${summary.indexed}, pominięte: ${summary.failed}."
                }
                performSearch()
            }
        })
    }

    private fun performSearch() {
        if (destroyed) return
        val generation = ++searchGeneration

        if (galleryAccess() == GalleryAccess.NONE) {
            adapter.submit(emptyList())
            binding.statusText.text = "Najpierw nadaj PhotoMind dostęp do zdjęć."
            return
        }

        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (repository.indexedCount() == 0) {
            adapter.submit(emptyList())
            binding.statusText.text = "Indeks jest pusty — najpierw uruchom indeksowanie."
            return
        }

        if (query.isBlank()) {
            repository.search("", null) { results ->
                if (!destroyed) {
                    runOnUiThread {
                        if (!destroyed && generation == searchGeneration) {
                            adapter.submit(results)
                            binding.statusText.text = "Ostatnio dostępne: ${results.size} zdjęć."
                        }
                    }
                }
            }
            return
        }

        translator.translatePolishToEnglish(
            query = query,
            onPreparing = {
                if (!destroyed) {
                    runOnUiThread {
                        if (!destroyed && generation == searchGeneration) {
                            binding.statusText.text = "Szukam lokalnie… Przy pierwszym użyciu może zostać pobrany model PL→EN."
                        }
                    }
                }
            },
            onResult = { translated ->
                if (!destroyed && generation == searchGeneration) {
                    repository.search(query, translated) { results ->
                        if (!destroyed) {
                            runOnUiThread {
                                if (!destroyed && generation == searchGeneration) {
                                    adapter.submit(results)
                                    binding.statusText.text = if (results.isEmpty()) {
                                        "Brak wyników dla „$query”. Spróbuj prostszych słów albo dodaj własny tag."
                                    } else {
                                        "Znaleziono ${results.size} zdjęć dla „$query”. Najtrafniejsze są na początku."
                                    }
                                }
                            }
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
            .setMessage("Możesz wpisać kilka słów oddzielonych spacją lub przecinkiem. Własne tagi mają najwyższy priorytet wyszukiwania.")
            .setView(input)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz") { _, _ ->
                repository.updateTags(photo, input.text.toString()) {
                    if (!destroyed) {
                        runOnUiThread {
                            if (!destroyed) {
                                adapter.itemChanged(photo)
                                Toast.makeText(this, "Tag zapisany lokalnie", Toast.LENGTH_SHORT).show()
                            }
                        }
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
            Toast.makeText(
                this,
                "Nie udało się otworzyć zdjęcia. Sprawdź, czy PhotoMind nadal ma do niego dostęp.",
                Toast.LENGTH_LONG
            ).show()
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

    private fun galleryAccess(): GalleryAccess = when {
        Build.VERSION.SDK_INT >= 34 -> {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED -> GalleryAccess.FULL

                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED -> GalleryAccess.PARTIAL

                else -> GalleryAccess.NONE
            }
        }
        Build.VERSION.SDK_INT >= 33 -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) GalleryAccess.FULL else GalleryAccess.NONE
        }
        else -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) GalleryAccess.FULL else GalleryAccess.NONE
        }
    }

    override fun onDestroy() {
        destroyed = true
        searchGeneration++
        translator.close()
        repository.close()
        super.onDestroy()
    }
}
