package com.example.callscheduler.data.ai
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

object ModelStore {
 fun file(context: Context, gemma: Boolean): File = File(File(context.filesDir,"models").apply{mkdirs()}, if(gemma) "gemma.task" else "whisper.bin")
 suspend fun install(context: Context, uri: Uri, gemma: Boolean, progress: (Long)->Unit) = withContext(Dispatchers.IO) {
  LocalModelLock.mutex.withLock {
   val target=file(context,gemma); val temp=File(target.parentFile,target.name+".partial")
   try {
    require(target.parentFile!!.usableSpace > 900L*1024*1024) { "모델 가져오기에 최소 900 MiB의 여유 공간이 필요합니다." }
    context.contentResolver.openInputStream(uri).use { input ->
     requireNotNull(input) { "선택한 파일을 읽을 수 없습니다." }
     temp.outputStream().use { out ->
      val buffer=ByteArray(1024*1024);var total=0L
      while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=1500L*1024*1024){"지원 모델 크기 초과"};out.write(buffer,0,n);progress(total)}
      out.fd.sync()
     }
    }
    if(gemma) {
     TaskBundleValidator.validate(temp)
     require(temp.length()==689308662L) { "이 버전은 Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task (689308662 bytes)를 지원합니다." }
    } else {
     require(temp.length() in 100_000_000L..1_100_000_000L) { "다국어 Whisper base 또는 small ggml .bin 파일을 선택하세요." }
     RandomAccessFile(temp,"r").use { require(it.readInt()==0x6c6d6767) { "Whisper GGML 모델 형식이 아닙니다." } }
     val handle=WhisperNative.open(temp.absolutePath);WhisperNative.close(handle)
    }
    check(temp.renameTo(target)) { "모델 설치 파일 교체 실패" }
   } finally { temp.delete() }
  }
 }
}

/** MediaPipe's official writer pads BEFORE local ZIP headers. Never use Android ZipFile,
 * strip the padding, or rename .litertlm to .task. Validate central offsets and CRC in place. */
object TaskBundleValidator {
 private fun RandomAccessFile.u16():Int {val a=readUnsignedByte();return a or(readUnsignedByte() shl 8)}
 private fun RandomAccessFile.u32():Long = u16().toLong() or(u16().toLong() shl 16)
 fun validate(file: File) {
  RandomAccessFile(file,"r").use { f ->
   require(f.length()>=22) { "빈 파일 또는 잘린 모델입니다." }
   var end=f.length()-22;val min=maxOf(0,f.length()-65557)
   while(end>=min){f.seek(end);if(f.u32()==0x06054b50L){f.seek(end+20);if(end+22+f.u16()==f.length())break};end--}
   require(end>=min) { "MediaPipe .task ZIP 번들이 아닙니다. .litertlm 파일은 지원하지 않습니다." }
   f.seek(end+4);require(f.u16()==0 && f.u16()==0)
   val count=f.u16();require(count==f.u16() && count in 1..32)
   val size=f.u32();val offset=f.u32();require(offset+size==end)
   data class Entry(val name:String,val crc:Long,val size:Long,val offset:Long)
   val entries=mutableListOf<Entry>();f.seek(offset)
   repeat(count){
    require(f.u32()==0x02014b50L);f.skipBytes(4);val flags=f.u16();val method=f.u16();f.skipBytes(4)
    val crc=f.u32();val compressed=f.u32();val unpacked=f.u32();val namesize=f.u16();val extra=f.u16();val comment=f.u16()
    require(flags and 1==0 && method==0 && compressed==unpacked && unpacked>0 && namesize in 1..256)
    f.skipBytes(8);val local=f.u32();val name=ByteArray(namesize);f.readFully(name);f.skipBytes(extra+comment)
    entries+=Entry(String(name,Charsets.UTF_8),crc,unpacked,local)
   }
   require(f.filePointer==end)
   require(entries.map{it.name}.toSet().size==count)
   require(entries.map{it.name}.containsAll(listOf("TF_LITE_PREFILL_DECODE","TOKENIZER_MODEL","METADATA"))) { "Gemma MediaPipe 번들의 필수 구성 요소가 없습니다." }
   var previousEnd=0L
   for(e in entries.sortedBy{it.offset}) {
    require(e.offset>=previousEnd && e.offset-previousEnd<=4) { "ZIP 헤더 위치 오류" }
    f.seek(previousEnd);while(f.filePointer<e.offset)require(f.readByte().toInt()==0)
    require(f.u32()==0x04034b50L);f.skipBytes(2);require(f.u16() and 1==0 && f.u16()==0);f.skipBytes(16)
    val nl=f.u16();val el=f.u16();require(nl in 1..256);val name=ByteArray(nl);f.readFully(name);require(String(name,Charsets.UTF_8)==e.name);f.skipBytes(el)
    require(f.filePointer+e.size<=offset);val crc=CRC32();var remaining=e.size;val buf=ByteArray(1024*1024)
    while(remaining>0){val n=minOf(remaining,buf.size.toLong()).toInt();f.readFully(buf,0,n);crc.update(buf,0,n);remaining-=n}
    require(crc.value==e.crc) { "모델 파일 일부가 손상되었습니다. 다시 다운로드하세요." };previousEnd=f.filePointer
   }
   require(previousEnd==offset)
  }
 }
}
