package com.example.callscheduler.ui
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.callscheduler.data.ai.ModelStore
import kotlinx.coroutines.launch

class ModelManagerActivity: ComponentActivity() {
 private var gemma=false
 private lateinit var status:TextView
 private lateinit var progress:TextView
 private val buttons=mutableListOf<Button>()
 private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){ uri ->
  if(uri!=null) lifecycleScope.launch {
   buttons.forEach{it.isEnabled=false}
   try {
    ModelStore.install(this@ModelManagerActivity,uri,gemma){bytes->runOnUiThread{progress.text="가져오기·검사 중: ${bytes/1024/1024} MiB"}}
    progress.text="설치 완료. 원본 다운로드 파일은 그대로 남아 있습니다."
   } catch(e:Exception){
    progress.text="설치 실패: ${e.message}\n기존 설치 모델은 유지됩니다."
    android.app.AlertDialog.Builder(this@ModelManagerActivity).setMessage(progress.text).setPositiveButton("확인",null)
     .setNeutralButton("오류 복사"){_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("모델 오류",progress.text))}.show()
   } finally { buttons.forEach{it.isEnabled=true};refresh() }
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState);gemma=savedInstanceState?.getBoolean("gemma")?:false
  val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,40,32,32)}
  val scroll=ScrollView(this).apply{addView(column)}
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->
   val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
   v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
  }
  setContentView(scroll)
  fun text(s:String)=TextView(this).apply{text=s;textSize=16f;setPadding(0,12,0,12);column.addView(this)}
  fun button(s:String,action:()->Unit){val b=Button(this).apply{text=s;setOnClickListener{action()}};buttons+=b;column.addView(b)}
  text("로컬 모델 관리").textSize=24f
  text("Whisper: 음성 → 텍스트\nGemma: 텍스트 → 일정\n다운로드는 브라우저에서 Downloads 폴더에 저장합니다. 아래 가져오기로 선택하면 앱이 내부 저장소에 복사합니다. 내부 폴더에 직접 접근할 필요가 없습니다.")
  status=text("");progress=text("")
  button("Whisper base 다운로드 페이지 · 약 142 MiB"){open("https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-base.bin")}
  button("Whisper small 다운로드 페이지 · 약 466 MiB"){open("https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-small.bin")}
  button("Whisper .bin 가져오기"){gemma=false;picker.launch(arrayOf("*/*"))}
  button("호환 Gemma .task 다운로드 페이지 · 약 657 MiB"){open("https://huggingface.co/litert-community/Gemma3-1B-IT/blob/main/Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task")}
  button("Gemma .task 가져오기"){gemma=true;picker.launch(arrayOf("*/*"))}
  text("Gemma는 Hugging Face 로그인 및 본인의 이용 조건 동의가 필요할 수 있습니다. .litertlm을 .task로 이름만 변경하지 마세요.\nWhisper는 다국어 base/small 모델을 사용하세요(.en 제외). small은 메모리·배터리·처리 시간이 더 필요합니다.\n가져오는 동안 이 화면을 유지하세요. 가져오기 중 앱이 종료되면 기존 모델이 보존되며 다시 가져오면 됩니다. 모델은 앱 삭제 시 함께 삭제됩니다.")
  button("닫기"){finish()};refresh()
 }
 private fun open(url:String){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
 private fun refresh(){status.text=listOf(false,true).joinToString("\n\n"){g->val f=ModelStore.file(this,g);"${if(g) "Gemma" else "Whisper"}: ${if(f.exists()) "설치됨 · ${f.length()/1024/1024} MiB" else "미설치"}\n${f.absolutePath}"}}
 override fun onSaveInstanceState(outState:Bundle){outState.putBoolean("gemma",gemma);super.onSaveInstanceState(outState)}
}
