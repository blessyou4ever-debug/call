package com.example.callscheduler
import com.example.callscheduler.data.ai.ScheduleJson
import com.example.callscheduler.data.ai.TaskBundleValidator
import com.example.callscheduler.data.parser.CallFilenameParser
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class LocalModelTests {
 private fun fixture(name:String):File {val f=File.createTempFile("bundle", ".task");javaClass.getResourceAsStream("/$name")!!.use{input->f.outputStream().use{input.copyTo(it)}};return f}
 @Test fun acceptsOfficialStyleLeadingPadding(){val f=fixture("aligned.task");try{TaskBundleValidator.validate(f)}finally{f.delete()}}
 @Test fun acceptsStandardStoredZip(){val f=fixture("standard.task");try{TaskBundleValidator.validate(f)}finally{f.delete()}}
 @Test(expected=IllegalArgumentException::class) fun rejectsCorruptedPayload(){val f=fixture("corrupt.task");try{TaskBundleValidator.validate(f)}finally{f.delete()}}
 @Test(expected=IllegalArgumentException::class) fun rejectsOtherFormats(){val f=fixture("wrong.task");try{TaskBundleValidator.validate(f)}finally{f.delete()}}
 @Test fun preservesUnknownTimes(){val result=ScheduleJson.parse("""{"actions":[{"type":"APPOINTMENT","title":"점심","evidence":"내일 점심에 봐요","start":null,"end":null,"location":null}]}""","내일 점심에 봐요");assertNull(result.single().startDateTimeIso);assertNull(result.single().endDateTimeIso)}
 @Test(expected=IllegalArgumentException::class) fun rejectsInventedEvidence(){ScheduleJson.parse("""{"actions":[{"type":"TODO","title":"작업","evidence":"없는 문장","start":null,"end":null}]}""","안녕하세요")}
 @Test(expected=IllegalArgumentException::class) fun rejectsInvalidEnd(){ScheduleJson.parse("""{"actions":[{"type":"APPOINTMENT","title":"미팅","evidence":"미팅","start":"2026-10-21T16:00:00","end":"2026-10-21T15:00:00"}]}""","미팅")}
 @Test fun supportsMultipleActions(){val a="""{"type":"TODO","title":"자료","evidence":"자료 보내줘","start":null,"end":null,"location":null}""";assertEquals(2,ScheduleJson.parse("""{"actions":[$a,$a]}""","자료 보내줘").size)}
 @Test fun filenameTimestampSurvivesCopy(){val f=File("통화 녹음 홍길동_241020_153000.m4a");assertEquals(2024,CallFilenameParser.recordedAtOrNull(f)!!.year)}
 @Test fun unknownFilenameNotAutomaticallyEligible(){assertNull(CallFilenameParser.recordedAtOrNull(File("old_recording.m4a")))}
}
