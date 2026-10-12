package com.einhander.temposcore.ui.browser

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.einhander.temposcore.R
import com.einhander.temposcore.databinding.DialogMidiBrowserBinding
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A landscape dialog within the Activity, with access bounded to one SAF tree. */
class MidiBrowserDialog : DialogFragment() {
    interface Host {
        fun chooseMidiFolder()
        fun chooseMidiWithSystemPicker()
        fun selectBrowserMidi(uri: Uri)
    }

    private var binding: DialogMidiBrowserBinding? = null
    private var tree: Uri? = null
    private var navigation: BrowserNavigation? = null
    private var rootName: String? = null
    private var listed: List<BrowserDocument> = emptyList()
    private val executor = Executors.newSingleThreadExecutor()
    private var pending: Future<*>? = null
    private var cancellation: CancellationSignal? = null
    private var generation = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        tree = arguments?.getString(KEY_TREE)?.let(Uri::parse)
        tree?.let { uri ->
            navigation = BrowserNavigation(BrowserDocument(DocumentsContract.getTreeDocumentId(uri),
                getString(R.string.browser_root), true))
            val ids = state?.getStringArrayList(KEY_IDS)
            val names = state?.getStringArrayList(KEY_NAMES)
            if (ids != null && names != null && ids.size == names.size && ids.firstOrNull() == navigation?.current?.id) {
                for (index in 1 until ids.size) {
                    val folder = BrowserDocument(ids[index], names[index], true)
                    if (navigation?.enter(folder, listOf(folder)) != true) break
                }
            }
        }
    }

    override fun onCreateDialog(state: Bundle?): Dialog {
        val views = DialogMidiBrowserBinding.inflate(LayoutInflater.from(requireContext()))
        binding = views
        views.chooseFolder.setText(if (tree == null) R.string.browser_choose_folder else R.string.browser_change_folder)
        views.chooseFolder.setOnClickListener {
            (requireActivity() as Host).chooseMidiFolder()
            dismiss()
        }
        views.systemPicker.setOnClickListener {
            (requireActivity() as Host).chooseMidiWithSystemPicker()
            dismiss()
        }
        views.closeBrowser.setOnClickListener { dismiss() }
        views.upFolder.setOnClickListener { goUp() }
        views.retryFolder.setOnClickListener { loadFolder() }
        views.documents.setOnItemClickListener { _, _, index, _ ->
            val document = listed.getOrNull(index) ?: return@setOnItemClickListener
            val nav = navigation ?: return@setOnItemClickListener
            if (document.directory) {
                if (nav.enter(document, listed)) loadFolder()
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree!!, document.id)
                (requireActivity() as Host).selectBrowserMidi(uri)
                dismiss()
            }
        }
        return AlertDialog.Builder(requireContext()).setView(views.root).create().apply {
            setCanceledOnTouchOutside(false)
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        if (!goUp()) dismiss()
                    }
                    true
                } else false
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.systemUiVisibility = requireActivity().window.decorView.systemUiVisibility
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        loadFolder()
    }

    private fun goUp(): Boolean {
        if (navigation?.up() != true) return false
        loadFolder()
        return true
    }

    private fun cancelQuery() {
        generation++
        cancellation?.cancel()
        cancellation = null
        pending?.cancel(true)
        pending = null
    }

    private fun loadFolder() {
        cancelQuery()
        val views = binding ?: return
        val nav = navigation
        views.upFolder.isEnabled = nav?.canGoUp == true
        updatePath()
        listed = emptyList()
        views.documents.adapter = null
        views.documents.isEnabled = false
        views.retryFolder.isEnabled = false
        views.browserHint.setText(R.string.browser_hint)
        showStatus(if (tree == null) R.string.browser_no_folder else R.string.browser_loading)
        val localTree = tree ?: return
        val folder = nav?.current ?: return
        val resolver = requireContext().applicationContext.contentResolver
        val signal = CancellationSignal()
        cancellation = signal
        val token = generation
        val weak = WeakReference(this)
        pending = executor.submit {
            val result = runCatching { MidiTreeRepository.list(resolver, localTree, folder.id, signal) }
            mainHandler.post { weak.get()?.acceptListing(token, result) }
        }
    }

    private fun acceptListing(token: Int, result: Result<BrowserListing>) {
        if (token != generation || !isAdded || dialog?.isShowing != true) return
        val views = binding ?: return
        views.retryFolder.isEnabled = true
        result.fold(onSuccess = { listing ->
            rootName = listing.rootName
            updatePath()
            listed = listing.documents
            views.documents.adapter = object : ArrayAdapter<BrowserDocument>(requireContext(), R.layout.midi_browser_item, listed) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: layoutInflater.inflate(R.layout.midi_browser_item, parent, false)
                    val document = getItem(position)!!
                    val kind = getString(if (document.directory) R.string.browser_folder else R.string.browser_midi)
                    row.findViewById<TextView>(R.id.documentKind).text = kind
                    row.findViewById<TextView>(R.id.documentName).text = document.name
                    row.contentDescription = "$kind: ${document.name}"
                    return row
                }
            }
            views.documents.isEnabled = true
            if (listing.documents.isEmpty()) {
                showStatus(if (listing.loading) R.string.browser_provider_loading else R.string.browser_empty)
            } else {
                views.browserStatus.visibility = View.GONE
            }
            if (listing.loading) views.browserHint.setText(R.string.browser_provider_loading)
        }, onFailure = { error ->
            showStatus(if (error is SecurityException) R.string.browser_access_error else R.string.browser_error)
        })
    }

    private fun showStatus(resource: Int) {
        binding?.browserStatus?.apply { setText(resource); visibility = View.VISIBLE }
    }

    private fun updatePath() {
        val views = binding ?: return
        views.folderPath.text = navigation?.path?.mapIndexed { index, folder ->
            if (index == 0) rootName ?: folder.name else folder.name
        }?.joinToString(" / ") ?: getString(R.string.browser_root)
        views.folderPath.contentDescription = views.folderPath.text
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        navigation?.path?.let { path ->
            out.putStringArrayList(KEY_IDS, ArrayList(path.map { it.id }))
            out.putStringArrayList(KEY_NAMES, ArrayList(path.map { it.name }))
        }
    }

    override fun onStop() {
        cancelQuery()
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val TAG = "midi_browser"
        private const val KEY_TREE = "tree"
        private const val KEY_IDS = "folder_ids"
        private const val KEY_NAMES = "folder_names"
        private val mainHandler = Handler(Looper.getMainLooper())
        fun create(tree: String?) = MidiBrowserDialog().apply {
            arguments = Bundle().apply { putString(KEY_TREE, tree) }
        }
    }
}
