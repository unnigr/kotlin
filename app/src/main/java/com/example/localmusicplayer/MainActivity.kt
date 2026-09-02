package com.example.localmusicplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutionException

class MainActivity : Activity() {
    companion object { const val PICK_FOLDER = 1001; const val PREFS = "music_player"; const val KEY_TRACKS = "tracks"; const val KEY_TREE = "tree" }

    private lateinit var playButton: Button; private lateinit var folderButton: Button; private lateinit var prevButton: Button; private lateinit var nextButton: Button
    private lateinit var seekBar: SeekBar; private lateinit var volumeBar: SeekBar; private lateinit var sleepButton: Button
    private lateinit var songTitle: TextView; private lateinit var songArtist: TextView; private lateinit var position: TextView; private lateinit var duration: TextView; private lateinit var subtitle: TextView
    private lateinit var playlistView: ListView; private lateinit var vinyl: VinylView
    private lateinit var prefs: SharedPreferences
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var tracks = mutableListOf<Track>()
    private var userSeeking = false
    private var sleepMinutes = 0
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { updateUi(); handler.postDelayed(this, 400) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews(); prefs = getSharedPreferences(PREFS, MODE_PRIVATE); setupUi(); requestNotificationPermission(); connectController()
    }

    private fun bindViews() {
        playButton=findViewById(R.id.playButton); folderButton=findViewById(R.id.folderButton); prevButton=findViewById(R.id.prevButton); nextButton=findViewById(R.id.nextButton)
        seekBar=findViewById(R.id.seekBar); volumeBar=findViewById(R.id.volumeBar); sleepButton=findViewById(R.id.sleepButton)
        songTitle=findViewById(R.id.songTitle); songArtist=findViewById(R.id.songArtist); position=findViewById(R.id.position); duration=findViewById(R.id.duration); subtitle=findViewById(R.id.subtitle)
        playlistView=findViewById(R.id.playlist); vinyl=findViewById(R.id.vinyl)
    }

    private fun setupUi() {
        folderButton.setOnClickListener { chooseFolder() }
        playButton.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        prevButton.setOnClickListener { controller?.seekToPreviousMediaItem() }
        nextButton.setOnClickListener { controller?.seekToNextMediaItem() }
        volumeBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) controller?.volume = p / 100f }
            override fun onStartTrackingTouch(s: SeekBar?) {} ; override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar?) { userSeeking=true }
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { }
            override fun onStopTrackingTouch(s: SeekBar?) { val c=controller; if(c!=null && c.duration>0) c.seekTo((c.duration * (seekBar.progress/1000f)).toLong()); userSeeking=false }
        })
        sleepButton.setOnClickListener {
            sleepMinutes = when(sleepMinutes) { 0->15; 15->30; 30->45; 45->60; else->0 }
            sleepButton.text = if(sleepMinutes==0) "SLEEP" else "${sleepMinutes}M"
            if(sleepMinutes>0) handler.postDelayed({ if(sleepMinutes>0) { controller?.pause(); sleepMinutes=0; sleepButton.text="SLEEP" } }, sleepMinutes*60_000L)
        }
        playlistView.setOnItemClickListener { _,_,pos,_ -> controller?.let { if(it.mediaItemCount==tracks.size) it.seekToDefaultPosition(pos); it.play() } }
        handler.post(ticker)
    }

    private fun requestNotificationPermission() {
        if(android.os.Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }

    private fun connectController() {
        val token=SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture=MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            try {
                controller=controllerFuture.get()
                controller!!.repeatMode=Player.REPEAT_MODE_OFF
                controller!!.addListener(object: Player.Listener {
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) { updateUi(); highlightCurrent() }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { updateUi() }
                    override fun onPlaybackStateChanged(state: Int) { updateUi() }
                    override fun onPlayerError(error: PlaybackException) { Toast.makeText(this@MainActivity, "Playback error: ${error.errorCodeName}", Toast.LENGTH_LONG).show(); subtitle.text="Playback error: ${error.errorCodeName}" }
                })
                restoreSaved()
            } catch(e: ExecutionException) { Toast.makeText(this,"Player connection failed",Toast.LENGTH_LONG).show() } catch(e: InterruptedException) { }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun restoreSaved() {
        val json=prefs.getString(KEY_TRACKS,null) ?: return
        val arr=JSONArray(json); loadTrackUi(arr); buildNativeQueue(arr,0,false)
        subtitle.text="${tracks.size} songs"
    }

    private fun chooseFolder() {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) }
        startActivityForResult(i,PICK_FOLDER)
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=PICK_FOLDER || resultCode!=RESULT_OK) return
        val tree=data?.data ?: return
        try { contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception) {}
        prefs.edit().putString(KEY_TREE,tree.toString()).apply()
        val found=mutableListOf<Track>(); scanTree(tree,found); found.sortBy{it.name.lowercase(Locale.getDefault())}; tracks=found
        val arr=JSONArray(); tracks.forEach { arr.put(JSONObject().put("name",it.name).put("uri",it.uri.toString())) }; prefs.edit().putString(KEY_TRACKS,arr.toString()).apply()
        loadTrackUi(arr); buildNativeQueue(arr,0,tracks.isNotEmpty()); subtitle.text="${tracks.size} songs"
    }

    private fun scanTree(tree:Uri,out:MutableList<Track>) { val root=DocumentsContract.getTreeDocumentId(tree); scanChildren(tree,root,out) }
    private fun scanChildren(tree:Uri,parent:String,out:MutableList<Track>) {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
        contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c ->
            while(c.moveToNext()) { val id=c.getString(0); val name=c.getString(1) ?: ""; val mime=c.getString(2) ?: ""; val doc=DocumentsContract.buildDocumentUriUsingTree(tree,id); if(mime==DocumentsContract.Document.MIME_TYPE_DIR) scanChildren(tree,id,out) else if(isAudio(name,mime)) out.add(Track(name,doc)) }
        }
    }

    private fun loadTrackUi(arr:JSONArray) {
        tracks=MutableList(arr.length()) { i -> val o=arr.getJSONObject(i); Track(o.getString("name"),Uri.parse(o.getString("uri"))) }
        val adapter=object: ArrayAdapter<Track>(this,R.layout.row_track,R.id.name,tracks) {
            override fun getView(pos:Int,convertView:View?,parent:android.view.ViewGroup):View { val v=super.getView(pos,convertView,parent); v.findViewById<TextView>(R.id.num).text=(pos+1).toString(); v.findViewById<TextView>(R.id.name).text=tracks[pos].name; return v }
        }
        playlistView.adapter=adapter
    }

    private fun buildNativeQueue(arr:JSONArray,start:Int,autoplay:Boolean) {
        val c=controller ?: return; val items=ArrayList<MediaItem>()
        for(i in 0 until arr.length()) { val o=arr.getJSONObject(i); val uri=Uri.parse(o.getString("uri")); val name=o.optString("name","Track"); val mime=mimeFromName(name) ?: contentResolver.getType(uri); val b=MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(name.substringBeforeLast('.')).setArtist("Local Music Folder").build()); if(!mime.isNullOrBlank()) b.setMimeType(mime); items.add(b.build()) }
        if(items.isEmpty()) return
        c.stop(); c.clearMediaItems(); c.setMediaItems(items,start.coerceIn(0,items.lastIndex),0); c.prepare(); if(autoplay) c.play()
    }

    private fun updateUi() {
        val c=controller ?: return; val playing=c.isPlaying; playButton.text=if(playing) "Ⅱ" else "▶"; vinyl.setPlaying(playing)
        val idx=c.currentMediaItemIndex
        if(idx>=0 && idx<tracks.size) { songTitle.text=tracks[idx].name.substringBeforeLast('.'); songArtist.text="Local Music Folder" }
        val dur=c.duration.coerceAtLeast(0); val pos=c.currentPosition.coerceAtLeast(0); position.text=formatTime(pos); duration.text=formatTime(dur); if(!userSeeking && dur>0) seekBar.progress=((pos.toDouble()/dur)*1000).toInt().coerceIn(0,1000)
    }
    private fun highlightCurrent() { playlistView.invalidateViews() }
    private fun formatTime(ms:Long):String { val s=ms/1000; return String.format(Locale.US,"%d:%02d",s/60,s%60) }
    private fun isAudio(name:String,mime:String)=mime.startsWith("audio/") || listOf(".mp3",".m4a",".wav",".flac",".ogg",".opus",".aac").any{name.lowercase(Locale.getDefault()).endsWith(it)}
    private fun mimeFromName(name:String):String?=when { name.endsWith(".mp3",true)->"audio/mpeg"; name.endsWith(".m4a",true)->"audio/mp4"; name.endsWith(".wav",true)->"audio/wav"; name.endsWith(".flac",true)->"audio/flac"; name.endsWith(".ogg",true)->"audio/ogg"; name.endsWith(".opus",true)->"audio/opus"; name.endsWith(".aac",true)->"audio/aac"; else->null }
    data class Track(val name:String,val uri:Uri)

    override fun onDestroy() { handler.removeCallbacks(ticker); controller?.release(); super.onDestroy() }
}
