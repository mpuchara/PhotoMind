package com.photomind.app

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.photomind.app.databinding.ActivityPeopleBinding

class PeopleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPeopleBinding
    private lateinit var repository: PhotoRepository
    private lateinit var adapter: PersonAdapter
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        repository = PhotoRepository(this)
        adapter = PersonAdapter(::showNameDialog)
        binding.peopleList.layoutManager = LinearLayoutManager(this)
        binding.peopleList.adapter = adapter
        loadPeople()
    }

    private fun applyInsets() {
        val root = binding.root
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun loadPeople() {
        repository.listPersonClusters { clusters ->
            if (destroyed) return@listPersonClusters
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                adapter.submit(clusters)
                binding.peopleStatus.text = if (clusters.isEmpty()) {
                    "Jeszcze nie mam powtarzających się twarzy. PhotoMind utworzy grupy automatycznie podczas indeksowania."
                } else {
                    "Znaleziono ${clusters.size} grup osób. Nadaj imię raz — wyszukiwanie obejmie wszystkie zdjęcia z tą osobą."
                }
            }
        }
    }

    private fun showNameDialog(cluster: PersonCluster) {
        val input = EditText(this).apply {
            setText(cluster.name)
            hint = "np. Maciek, Tadzio, Kasia"
            setPadding(48, 20, 48, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(if (cluster.name.isBlank()) "Kto to?" else "Zmień imię")
            .setMessage("Imię zostanie przypisane do całej grupy podobnych twarzy, nie do pojedynczego zdjęcia.")
            .setView(input)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz") { _, _ ->
                repository.namePersonCluster(cluster.id, input.text.toString()) {
                    if (!destroyed) runOnUiThread { loadPeople() }
                }
            }
            .show()
    }

    override fun onDestroy() {
        destroyed = true
        repository.close()
        super.onDestroy()
    }
}
