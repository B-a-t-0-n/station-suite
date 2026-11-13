package local.station.voicebridge;

import org.json.JSONObject;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the actual orchestration with artificial bytes and in-memory providers only. */
public final class VoicePipelineHostTest {
    private static int assertions;
    private static final String PRIVATE = "synthetic-private-marker-never-in-report";
    private static void check(boolean value, String name) {
        assertions++; if (!value) throw new AssertionError(name);
    }
    private static byte[] input(int size) {
        byte[] audio = new byte[size];
        if (size >= 44) {
            audio[0]='R';audio[1]='I';audio[2]='F';audio[3]='F';
            audio[8]='W';audio[9]='A';audio[10]='V';audio[11]='E';
        }
        return audio;
    }
    private static JSONObject config() throws Exception { return new JSONObject().put("privateKey",PRIVATE); }
    private static JSONObject asr(String text) throws Exception {
        return new JSONObject().put("ok",true).put("text",text).put("fileDeleted",true)
                .put("usage",new JSONObject().put("total_tokens",17).put("precached_prompt_tokens",4)
                        .put("unknown",PRIVATE).put("prompt_tokens",-1).put("completion_tokens",1.5));
    }
    private static JSONObject answer(String text) throws Exception {
        return new JSONObject().put("ok",true).put("text",text)
                .put("usage",new JSONObject().put("total_tokens",11).put("unknown",PRIVATE));
    }
    private static VoicePipeline.Speech speech() {
        return new VoicePipeline.Speech(new byte[]{'O','g','g','S',0,1},4,"ogg");
    }
    private static final class Clock implements VoicePipeline.Clock {
        long now;
        public synchronized long millis() { return now++; }
    }
    private static class Fake implements VoicePipeline.Backend {
        JSONObject recognized, answered, understood;
        VoicePipeline.Speech spoken=speech();
        String prompt, text;
        int validations, transcriptions, understandings, answers, syntheses;
        Exception validateError, asrError, answerError, ttsError;
        boolean interruptAfterAsr;
        Fake() throws Exception { recognized=asr("Вопрос без личных данных.");answered=answer("Короткий ответ."); }
        public void validate(JSONObject settings) throws Exception {
            validations++;check(PRIVATE.equals(settings.getString("privateKey")),"config passed in memory");
            settings.put("snapshot-only",true);
            if(validateError!=null)throw validateError;
        }
        public JSONObject transcribe(JSONObject settings,byte[] bytes) throws Exception {
            transcriptions++;check(settings.optBoolean("snapshot-only"),"same settings snapshot");
            if(asrError!=null)throw asrError;
            if(interruptAfterAsr)Thread.currentThread().interrupt();
            return recognized;
        }
        public JSONObject ask(JSONObject settings,String input) throws Exception {
            answers++;prompt=input;if(answerError!=null)throw answerError;return answered;
        }
        public JSONObject understandAudio(JSONObject settings,byte[] bytes) throws Exception {
            understandings++;
            check(settings.optBoolean("snapshot-only"),"combined uses same snapshot");
            if(asrError!=null)throw asrError;
            return understood;
        }
        public VoicePipeline.Speech synthesize(JSONObject settings,String input) throws Exception {
            syntheses++;text=input;if(ttsError!=null)throw ttsError;return spoken;
        }
    }
    private static VoicePipeline.PipelineException fail(VoicePipeline pipeline,JSONObject config,byte[] audio,
                                                       String stage,String code) throws Exception {
        try { pipeline.run(config,audio);throw new AssertionError("expected failure "+code); }
        catch(VoicePipeline.PipelineException e) {
            check(stage.equals(e.stage),"failed stage "+stage);
            check(code.equals(e.code),"failed code "+code);
            check(e.getCause()==null,"no private exception cause");
            check(!e.getMessage().contains(PRIVATE),"no marker in error");
            check(!e.summary.toString().contains(PRIVATE),"no marker in failure summary");
            check(!e.summary.optBoolean("ok",true),"error summary not successful");
            return e;
        }
    }
    private static VoicePipeline pipeline(Fake fake) { return new VoicePipeline(fake,new Clock()); }
    private static String repeat(char value,int length) { char[] a=new char[length];Arrays.fill(a,value);return new String(a); }

    private static void happyAndSummary() throws Exception {
        Fake fake=new Fake();JSONObject settings=config();
        fake.recognized=asr("  Сколько будет два плюс два?  ");
        fake.answered=answer("  Четыре.  ");
        VoicePipeline.Result result=pipeline(fake).run(settings,input(44));
        check(result.transcript.equals("Сколько будет два плюс два?"),"trimmed transcript in memory");
        check(result.answer.equals("Четыре."),"trimmed answer in memory");
        check(fake.prompt.endsWith(result.transcript),"transcript enters prompt");
        check(fake.prompt.contains("не управляешь устройствами"),"no control execution instruction");
        check(fake.prompt.contains("одним коротким предложением"),"short Russian answer instruction");
        check(fake.text.equals(result.answer),"answer enters TTS");
        check(fake.validations==1&&fake.transcriptions==1&&fake.answers==1&&fake.syntheses==1,"one call per stage");
        check(!settings.has("snapshot-only"),"source config unmodified");
        check(result.summary.optBoolean("ok"),"successful summary");
        check(result.summary.optBoolean("fileDeleted"),"ASR cleanup kept");
        check(!result.summary.optBoolean("audioPlayed",true),"no playback");
        check(!result.summary.optBoolean("controlActionsExecuted",true),"no control actions");
        check(result.summary.getJSONObject("asrUsage").length()==2,"usage strict allowlist and integer counts");
        check(result.summary.getJSONObject("llmUsage").length()==1,"answer usage allowlist");
        check(!result.summary.toString().contains(PRIVATE),"no key in summary");
        check(!result.summary.toString().contains(result.transcript),"no transcript in summary");
        check(!result.summary.toString().contains(result.answer),"no answer in summary");
        check(result.summary.getInt("audioBytes")==result.audio.length,"audio count");
        check(result.summary.getLong("asrMs")>=0&&result.summary.getLong("llmMs")>=0
                &&result.summary.getLong("ttsMs")>=0&&result.summary.getLong("elapsedMs")>=0,"stage timers");
    }
    private static void rejectedInputs() throws Exception {
        for(byte[] audio:new byte[][]{null,new byte[0],new byte[43],input(VoicePipeline.MAX_AUDIO_INPUT_BYTES+1)}){
            Fake f=new Fake();fail(pipeline(f),config(),audio,"input","INVALID_AUDIO");
            check(f.validations==0&&f.transcriptions==0&&f.answers==0&&f.syntheses==0,"invalid input never reaches providers");
        }
        Fake f=new Fake();fail(pipeline(f),null,input(44),"input","INVALID_CONFIG");
        check(f.validations==0,"missing config never reaches providers");
        f=new Fake();f.validateError=new IOException(PRIVATE);VoicePipeline p=pipeline(f);
        fail(p,config(),input(44),"config","STAGE_FAILED");
        check(f.transcriptions==0,"preflight fails before upload");
        f.validateError=null;p.run(config(),input(44));check(f.transcriptions==1,"failed run releases ownership");
        f=new Fake();pipeline(f).run(config(),input(VoicePipeline.MAX_AUDIO_INPUT_BYTES));
        check(f.transcriptions==1,"input boundary accepted");
        byte[] ogg=new byte[27];ogg[0]='O';ogg[1]='g';ogg[2]='g';ogg[3]='S';
        f=new Fake();pipeline(f).run(config(),ogg);check(f.transcriptions==1,"Ogg fixture allowed");
    }
    private static void deletionAndAsr() throws Exception {
        for(Object deletion:new Object[]{Boolean.FALSE,"true",JSONObject.NULL}){
            Fake f=new Fake();f.recognized.put("fileDeleted",deletion);
            VoicePipeline.PipelineException e=fail(pipeline(f),config(),input(44),"asr","ASR_FILE_NOT_DELETED");
            check(!e.summary.optBoolean("fileDeleted",true),"deletion unproved");
            check(f.answers==0&&f.syntheses==0,"no next call after failed deletion");
        }
        Fake f=new Fake();f.recognized.remove("fileDeleted");fail(pipeline(f),config(),input(44),"asr","ASR_FILE_NOT_DELETED");
        for(Object text:new Object[]{" ",repeat('a',1001),4,"broken\ud800","control\u0000"}){
            f=new Fake();f.recognized.put("text",text);fail(pipeline(f),config(),input(44),"asr","INVALID_TRANSCRIPT");
            check(f.answers==0&&f.syntheses==0,"invalid transcript stops follow-up calls");
        }
        f=new Fake();f.recognized.put("ok",false);fail(pipeline(f),config(),input(44),"asr","BAD_ASR_RESPONSE");
        f=new Fake();f.recognized=null;fail(pipeline(f),config(),input(44),"asr","BAD_ASR_RESPONSE");
        f=new Fake();f.asrError=new IOException(PRIVATE);fail(pipeline(f),config(),input(44),"asr","STAGE_FAILED");
        check(f.answers==0&&f.syntheses==0,"ASR error has no retry or continuation");
    }
    private static void answerAndTtsErrors() throws Exception {
        for(Object text:new Object[]{" ",repeat('a',1001),4,"broken\udc00"}){
            Fake f=new Fake();f.answered.put("text",text);
            VoicePipeline.PipelineException e=fail(pipeline(f),config(),input(44),"llm","INVALID_ANSWER");
            check(e.summary.optBoolean("fileDeleted"),"cleanup still known after answer failure");
            check(f.syntheses==0,"invalid answer never synthesised");
        }
        Fake f=new Fake();f.answered.put("ok",false);fail(pipeline(f),config(),input(44),"llm","BAD_ANSWER_RESPONSE");
        f=new Fake();f.answerError=new IOException(PRIVATE);fail(pipeline(f),config(),input(44),"llm","STAGE_FAILED");
        check(f.answers==1&&f.syntheses==0,"no answer retry");
        for(VoicePipeline.Speech s:new VoicePipeline.Speech[]{null,
                new VoicePipeline.Speech(null,1,"ogg"),new VoicePipeline.Speech(new byte[4],1,"ogg"),
                new VoicePipeline.Speech(new byte[]{'O','g','g','S'},1,"mp3"),
                new VoicePipeline.Speech(new byte[VoicePipeline.MAX_AUDIO_OUTPUT_BYTES+1],1,"ogg"),
                new VoicePipeline.Speech(new byte[]{'O','g','g','S'},-1,"ogg")}){
            f=new Fake();f.spoken=s;fail(pipeline(f),config(),input(44),"tts","BAD_AUDIO_RESPONSE");
            check(f.syntheses==1,"no TTS retry after bad response");
        }
        f=new Fake();f.ttsError=new IOException(PRIVATE);fail(pipeline(f),config(),input(44),"tts","STAGE_FAILED");
    }
    private static void interruptionAndProviderErrors() throws Exception {
        Fake f=new Fake();VoicePipeline p=pipeline(f);Thread.currentThread().interrupt();
        try { fail(p,config(),input(44),"input","CANCELLED");check(Thread.currentThread().isInterrupted(),"interrupt retained"); }
        finally { Thread.interrupted(); }
        f=new Fake();f.interruptAfterAsr=true;
        try { fail(pipeline(f),config(),input(44),"asr","CANCELLED");check(f.answers==0,"interruption stops next API"); }
        finally { Thread.interrupted(); }
        Constructor<CloudClient.CloudException> cc=CloudClient.CloudException.class.getDeclaredConstructor(String.class,String.class,int.class);
        cc.setAccessible(true);f=new Fake();f.answerError=cc.newInstance("gigachat","HTTP_ERROR",429);
        VoicePipeline.PipelineException e=fail(pipeline(f),config(),input(44),"llm","HTTP_ERROR");
        check(e.provider.equals("gigachat")&&e.httpStatus==429,"safe Giga error fields preserved");
        Constructor<SpeechifyClient.SpeechifyException> sc=SpeechifyClient.SpeechifyException.class.getDeclaredConstructor(String.class,int.class,int.class);
        sc.setAccessible(true);f=new Fake();f.ttsError=sc.newInstance("RATE_LIMITED",429,12);
        e=fail(pipeline(f),config(),input(44),"tts","RATE_LIMITED");
        check(e.provider.equals("speechify")&&e.httpStatus==429&&e.retryAfterSeconds==12,"safe Speechify error fields preserved");
    }
    private static void concurrentRun() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Fake f=new Fake(){public JSONObject transcribe(JSONObject settings,byte[] audio)throws Exception{
            entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IOException("test deadline");
            return super.transcribe(settings,audio);
        }};
        VoicePipeline p=pipeline(f);AtomicReference<Throwable> thrown=new AtomicReference<>();
        Thread t=new Thread(()->{try{p.run(config(),input(44));}catch(Throwable e){thrown.set(e);}});
        t.start();check(entered.await(2,TimeUnit.SECONDS),"first run entered ASR");
        try { fail(p,config(),input(44),"input","BUSY"); }
        finally {release.countDown();t.join(3000);}
        check(!t.isAlive()&&thrown.get()==null,"first run survives concurrent rejection");
        check(f.validations==1&&f.transcriptions==1&&f.answers==1&&f.syntheses==1,"concurrent rejection does not duplicate calls");
    }

    private static final class Capture implements VoicePipeline.Output {
        int begins,writes,finishes,bytes;
        boolean cancelled,failWrite,failBegin,failFinish,cancelOnWrite;
        String transcript,answer;
        ByteArrayOutputStream audio=new ByteArrayOutputStream();
        StreamingFake backend;
        public void begin(String transcript,String answer)throws IOException{
            begins++;this.transcript=transcript;this.answer=answer;
            if(failBegin)throw new IOException(PRIVATE);
        }
        public void write(byte[] page,int offset,int count)throws IOException{
            check(begins==1&&finishes==0,"stream writes only between begin/finish");
            if(backend!=null)check(!backend.returned,"incremental callback happens before synthesis returns");
            writes++;bytes+=count;
            if(failWrite)throw new IOException(PRIVATE);
            audio.write(page,offset,count);
            if(cancelOnWrite)cancelled=true;
        }
        public void finish()throws IOException{
            finishes++;
            if(backend!=null)check(backend.returned,"finish after provider validates whole stream");
            if(failFinish)throw new IOException(PRIVATE);
        }
        public boolean isCancelled(){return cancelled;}
    }
    private static class StreamingFake extends Fake {
        byte[][] pages=pages();
        int streams,returnedBytes=-1,billable=-1;
        String format="ogg";
        boolean returned,badRange,noBytes,tooMany;
        Exception streamError;
        StreamingFake()throws Exception{}
        public VoicePipeline.StreamSpeech synthesizeStreaming(JSONObject settings,String answer,VoicePipeline.AudioSink sink)throws Exception{
            streams++;text=answer;int bytes=0;
            if(noBytes){returned=true;return new VoicePipeline.StreamSpeech(0,billable,format,1,2);}
            for(byte[] page:pages){
                if(sink.isCancelled())throw new IOException("cancelled");
                sink.write(page,0,page.length);bytes+=page.length;
                if(badRange){sink.write(page,page.length,1);throw new AssertionError("invalid range accepted");}
                if(streamError!=null)throw streamError;
            }
            if(tooMany){
                byte[] oversized=new byte[65307];
                while(true){sink.write(oversized,0,oversized.length);bytes+=oversized.length;}
            }
            returned=true;
            return new VoicePipeline.StreamSpeech(returnedBytes>=0?returnedBytes:bytes,billable,format,3,7);
        }
    }
    private static VoicePipeline.PipelineException streamFail(VoicePipeline pipeline,JSONObject config,Capture output,
                                                               String stage,String code)throws Exception{
        try{pipeline.runStreaming(config,input(44),output);throw new AssertionError("missing stream "+code);}
        catch(VoicePipeline.PipelineException e){
            check(stage.equals(e.stage)&&code.equals(e.code),"stream failure "+stage+"/"+code+" got "+e.stage+"/"+e.code);
            check(e.getCause()==null&&!e.toString().contains(PRIVATE)&&!e.summary.toString().contains(PRIVATE),"stream error excludes private text/cause");
            check(!e.summary.optBoolean("ok"),"stream failure not successful");return e;
        }
    }
    private static void streamingContract()throws Exception{
        StreamingFake fake=new StreamingFake();Capture output=new Capture();output.backend=fake;
        fake.recognized=asr(PRIVATE+" transcript");fake.answered=answer(PRIVATE+" answer");
        fake.recognized.put("timings",new JSONObject().put("oauthMs",2).put("uploadMs",3).put("audioChatMs",4).put("deleteMs",5)
                .put("private",PRIVATE));
        VoicePipeline.Result result=pipeline(fake).runStreaming(config(),input(44),output);
        check(fake.streams==1&&fake.syntheses==0&&fake.answers==1,"stream override used without batch call or retry");
        check(output.begins==1&&output.writes==4&&output.finishes==1,"one begin, incremental pages, one finish");
        check(result.audio==null&&output.bytes==result.summary.getInt("audioBytes"),"stream result does not retain full audio");
        check(output.transcript.equals(result.transcript)&&output.answer.equals(result.answer),"validated text passed in memory to begin");
        check(result.summary.optBoolean("streamingAudio")&&result.summary.getInt("billableCharacters")==-1,"unknown stream billing preserved");
        check(result.summary.getLong("ttsFirstByteMs")==3&&result.summary.getLong("ttsFirstAudioMs")==7
                &&result.summary.getLong("firstAudioReadyMs")>=7,"stream first audio timings");
        check(result.summary.getLong("asrUploadMs")==3&&!result.summary.has("asrPrivate"),"ASR timings fixed allowlist");
        check(!result.summary.toString().contains(PRIVATE),"stream summary excludes transcript/answer/key");

        Fake fallback=new Fake();fallback.spoken=new VoicePipeline.Speech(join(pages()),9,"ogg");
        Capture fallbackOutput=new Capture();
        result=pipeline(fallback).runStreaming(config(),input(44),fallbackOutput);
        check(fallback.syntheses==1&&fallbackOutput.writes==4&&fallbackOutput.begins==1&&fallbackOutput.finishes==1,"default backend splits buffered audio into full pages");
        check(Arrays.equals(fallback.spoken.audio,fallbackOutput.audio.toByteArray()),"default backend preserves page bytes/order");
        check(result.summary.getInt("billableCharacters")==9&&result.summary.getLong("ttsFirstAudioMs")==-1,"fallback billing/timing distinct from real stream");

        for(int type=0;type<4;type++){
            fake=new StreamingFake();output=new Capture();
            if(type==0)fake.returnedBytes=100;
            if(type==1)fake.format="mp3";
            if(type==2)fake.billable=-2;
            if(type==3)fake.noBytes=true;
            streamFail(pipeline(fake),config(),output,"tts","BAD_AUDIO_RESPONSE");
            check(output.finishes==0,"malformed stream has no finish/EOS");
        }
        fake=new StreamingFake();fake.badRange=true;output=new Capture();
        streamFail(pipeline(fake),config(),output,"tts","STAGE_FAILED");
        check(output.begins==1&&output.writes==1&&output.finishes==0,"bad partial callback cannot finish");
        fake=new StreamingFake();fake.tooMany=true;output=new Capture();
        streamFail(pipeline(fake),config(),output,"tts","STAGE_FAILED");
        check(output.bytes<=VoicePipeline.MAX_AUDIO_OUTPUT_BYTES&&output.finishes==0,"incremental byte cap enforced before excess output");
        for(int type=0;type<3;type++){
            fake=new StreamingFake();output=new Capture();
            output.failBegin=type==0;output.failWrite=type==1;output.failFinish=type==2;
            streamFail(pipeline(fake),config(),output,"tts","STAGE_FAILED");
            check(fake.streams==1&&fake.syntheses==0,"consumer failure never retries batch");
        }
        fake=new StreamingFake();fake.streamError=new IOException(PRIVATE);output=new Capture();
        streamFail(pipeline(fake),config(),output,"tts","STAGE_FAILED");
        check(output.writes==1&&output.finishes==0,"upstream failure after partial page never finishes");
    }
    private static void streamingCancellationAndCleanup()throws Exception{
        final Capture cancelledAfterAsr=new Capture();
        Fake f=new Fake(){public JSONObject transcribe(JSONObject settings,byte[] audio)throws Exception{
            JSONObject response=super.transcribe(settings,audio);cancelledAfterAsr.cancelled=true;return response;
        }};
        streamFail(pipeline(f),config(),cancelledAfterAsr,"asr","CANCELLED");
        check(f.transcriptions==1&&f.answers==0&&f.syntheses==0&&cancelledAfterAsr.begins==0,"cancel after ASR stops LLM/TTS/native output");
        StreamingFake fake=new StreamingFake();Capture output=new Capture();output.cancelOnWrite=true;
        streamFail(pipeline(fake),config(),output,"tts","CANCELLED");
        check(output.writes==1&&output.finishes==0,"cancel after first page suppresses remaining output/finish");
        for(Object deleted:new Object[]{false,"true",JSONObject.NULL}){
            fake=new StreamingFake();fake.recognized.put("fileDeleted",deleted);output=new Capture();
            streamFail(pipeline(fake),config(),output,"asr","ASR_FILE_NOT_DELETED");
            check(fake.answers==0&&fake.streams==0&&output.begins==0,"cleanup gate precedes stream/native start");
        }
        fake=new StreamingFake();fake.recognized.put("fileDeleted",false);VoicePipeline p=pipeline(fake);output=new Capture();
        streamFail(p,config(),output,"asr","ASR_FILE_NOT_DELETED");
        fake.recognized.put("fileDeleted",true);p.runStreaming(config(),input(44),new Capture());
        check(fake.streams==1,"stream owner reusable after failed cleanup gate");
    }
    private static void combinedAudioAnswer()throws Exception{
        StreamingFake f=new StreamingFake();
        f.understood=asr(PRIVATE+" recognized").put("answer",PRIVATE+" combined answer");
        Capture output=new Capture();
        VoicePipeline.Result result=pipeline(f).runStreaming(config().put("voice_direct_audio",true),input(44),output);
        check(f.understandings==1&&f.transcriptions==0&&f.answers==0&&f.streams==1,"combined audio uses one understand call, no separate ASR/ask");
        check(result.answer.equals(f.understood.getString("answer"))&&f.text.equals(result.answer),"combined answer flows directly to streaming TTS");
        check(result.summary.optBoolean("combinedAudioAnswer")&&!result.summary.toString().contains(PRIVATE),"combined result metadata excludes text");
        for(Object deleted:new Object[]{false,"true",JSONObject.NULL}){
            f=new StreamingFake();f.understood=asr("Вопрос").put("answer","Ответ").put("fileDeleted",deleted);output=new Capture();
            streamFail(pipeline(f),config().put("voice_direct_audio",true),output,"asr","ASR_FILE_NOT_DELETED");
            check(f.streams==0&&f.answers==0&&output.begins==0,"combined cleanup gating before TTS");
        }
        for(Object answer:new Object[]{JSONObject.NULL,3," ",repeat('a',1001),"broken\ud800"}){
            f=new StreamingFake();f.understood=asr("Вопрос").put("answer",answer);output=new Capture();
            streamFail(pipeline(f),config().put("voice_direct_audio",true),output,"llm","INVALID_ANSWER");
            check(f.streams==0&&output.begins==0,"invalid combined answer never synthesized");
        }
        f=new StreamingFake();output=new Capture();
        result=pipeline(f).runStreaming(config().put("voice_direct_audio","true"),input(44),output);
        check(f.understandings==0&&f.transcriptions==1&&f.answers==1&&!result.summary.optBoolean("combinedAudioAnswer"),"combined path requires actual boolean opt-in");
    }
    private static void finalLocalTranscript() throws Exception {
        StreamingFake fake = new StreamingFake(); Capture output = new Capture();
        String local = "  Локальный вопрос без загрузки аудио  ";
        VoicePipeline.Result result = pipeline(fake).runTranscript(config().put("voice_direct_audio",true),local,output);
        check(fake.transcriptions==0&&fake.understandings==0,"local final text never uploads or transcribes audio");
        check(fake.answers==1&&fake.streams==1,"local final text calls answer and streaming TTS once");
        check(fake.prompt.endsWith(local.trim())&&result.transcript.equals(local.trim()),"final transcript used as prompt");
        check(result.summary.optBoolean("localAsr")&&!result.summary.optBoolean("cloudAudioUploaded",true)
                &&!result.summary.optBoolean("fileDeletionRequired",true),"local summary distinguishes no remote file");
        check(!result.summary.optBoolean("combinedAudioAnswer",true),"local mode overrides combined audio setting");
        check(!result.summary.toString().contains(local.trim()),"no local transcript in summary");
        for(String invalid:new String[]{null," ",repeat('я',VoicePipeline.MAX_TRANSCRIPT_CHARS+1),"bad\u0000","bad\ud800"}){
            fake=new StreamingFake();output=new Capture();
            try {pipeline(fake).runTranscript(config(),invalid,output);throw new AssertionError("invalid local transcript accepted");}
            catch(VoicePipeline.PipelineException error){check("INVALID_TRANSCRIPT".equals(error.code),"invalid local final text rejected");}
            check(fake.transcriptions==0&&fake.understandings==0&&fake.answers==0&&fake.streams==0,
                    "invalid local text has no cloud ASR or followup API calls");
        }
        fake=new StreamingFake();output=new Capture();output.cancelled=true;
        try {pipeline(fake).runTranscript(config(),"Локальный вопрос",output);throw new AssertionError("cancelled local output accepted");}
        catch(VoicePipeline.PipelineException error){check("CANCELLED".equals(error.code),"stale local final text cancelled");}
        check(fake.transcriptions==0&&fake.understandings==0&&fake.answers==0&&fake.streams==0,
                "cancelled local final text has no API calls");
    }
    private static void fixedLocalFailureFeedback() throws Exception {
        String transcript = "  " + PRIVATE + " включи https://example.invalid/private-song  ";
        String[] phrases = {"Сейчас не получается включить музыку.", "Не удалось найти эту музыку.",
                "Сейчас не получается выполнить эту команду.", "Таймер установлен.",
                "Не удалось подтвердить установку таймера. Проверь раздел Часы в пульте.",
                "Активного таймера нет.", "Сейчас несколько таймеров. Скажи: останови все таймеры.",
                "Не удалось подтвердить остановку таймера. Проверь раздел Часы в пульте."};
        int index = 0;
        for (VoicePipeline.LocalFeedback feedback : VoicePipeline.LocalFeedback.values()) {
            StreamingFake fake = new StreamingFake(); Capture output = new Capture(); output.backend = fake;
            fake.asrError = new IOException(PRIVATE); fake.answerError = new IOException(PRIVATE);
            VoicePipeline.Result result = pipeline(fake).runFeedback(config().put("voice_direct_audio", true),
                    transcript, feedback, output);
            check(fake.transcriptions == 0 && fake.understandings == 0 && fake.answers == 0,
                    "fixed feedback never uploads audio, transcribes or calls LLM");
            check(fake.validations == 1 && fake.streams == 1 && fake.syntheses == 0,
                    "fixed feedback uses validated common streaming TTS exactly once");
            check(phrases[index++].equals(result.answer) && result.answer.equals(fake.text)
                    && output.answer.equals(result.answer), "fixed enum phrase is the only synthesized answer");
            check(transcript.trim().equals(result.transcript) && result.transcript.equals(output.transcript),
                    "feedback preserves finalized transcript in native reply only");
            check(!fake.text.contains(PRIVATE) && !fake.text.contains("https://"),
                    "feedback never echoes query or URL into synthesis");
            check(output.begins == 1 && output.finishes == 1 && output.writes == 4 && result.audio == null,
                    "feedback shares incremental begin/pages/finish without retaining audio");
            JSONObject summary = result.summary;
            check("local_command_speechify_ogg".equals(summary.getString("pipeline"))
                    && feedback.name().equals(summary.getString("localFeedback")), "fixed diagnostic mode only");
            check(summary.getInt("inputBytes") == 0 && summary.getInt("asrMs") == 0 && summary.getInt("llmMs") == 0
                    && !summary.optBoolean("cloudAudioUploaded", true) && !summary.optBoolean("fileDeletionRequired", true)
                    && !summary.optBoolean("fileDeleted", true) && !summary.optBoolean("combinedAudioAnswer", true),
                    "feedback reports no remote ASR file or model stage");
            check(!summary.optBoolean("controlActionsExecuted", true) && !summary.optBoolean("audioPlayed", true),
                    "feedback makes no action or playback success claim");
            check(!summary.toString().contains(PRIVATE) && !summary.toString().contains(result.answer)
                    && !summary.has("llmUsage") && !summary.has("asrUsage"), "summary excludes speech and provider usage");
        }
        Fake buffered = new Fake();
        VoicePipeline.Result bufferedResult = pipeline(buffered).runFeedback(config(), transcript,
                VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE, null);
        check(buffered.transcriptions == 0 && buffered.answers == 0 && buffered.syntheses == 1
                && bufferedResult.audio != null && transcript.trim().equals(bufferedResult.transcript),
                "explicit buffered fallback also bypasses ASR and LLM");

        for (String invalid : new String[]{null, " ", repeat('я', VoicePipeline.MAX_TRANSCRIPT_CHARS + 1),
                "bad\u0000", "bad\ud800", "bad\udc00"}) {
            StreamingFake fake = new StreamingFake();
            try { pipeline(fake).runFeedback(config(), invalid, VoicePipeline.LocalFeedback.MUSIC_UNAVAILABLE,
                    new Capture()); throw new AssertionError("malformed feedback transcript accepted"); }
            catch (VoicePipeline.PipelineException failure) {
                check("INVALID_TRANSCRIPT".equals(failure.code), "malformed feedback transcript rejected");
            }
            check(fake.validations == 0 && fake.transcriptions == 0 && fake.understandings == 0
                    && fake.answers == 0 && fake.streams == 0, "malformed feedback has no provider calls");
        }
        StreamingFake fake = new StreamingFake();
        try { pipeline(fake).runFeedback(config(), transcript, null, new Capture());
            throw new AssertionError("null feedback enum accepted"); }
        catch (VoicePipeline.PipelineException failure) {
            check("INVALID_FEEDBACK".equals(failure.code), "missing feedback enum rejected");
        }
        check(fake.validations == 0 && fake.streams == 0 && fake.answers == 0, "null enum has no provider calls");

        for (boolean cancelAfterPage : new boolean[]{false, true}) {
            fake = new StreamingFake(); Capture output = new Capture();
            output.cancelled = !cancelAfterPage; output.cancelOnWrite = cancelAfterPage;
            try { pipeline(fake).runFeedback(config(), transcript, VoicePipeline.LocalFeedback.COMMAND_UNAVAILABLE,
                    output); throw new AssertionError("cancelled feedback completed"); }
            catch (VoicePipeline.PipelineException failure) {
                check("CANCELLED".equals(failure.code), "cancelled feedback retains cancellation classification");
            }
            check(fake.transcriptions == 0 && fake.answers == 0 && output.finishes == 0,
                    "cancelled feedback neither runs model nor completes stale output");
            check(cancelAfterPage ? fake.streams == 1 && output.writes == 1 : fake.streams == 0 && output.begins == 0,
                    "feedback cancellation stops before API or after first page");
        }
    }
    private static byte[][] pages(){
        byte[] head=new byte[19];byte[] signature={'O','p','u','s','H','e','a','d'};
        System.arraycopy(signature,0,head,0,8);head[8]=1;head[9]=1;
        byte[] tags=new byte[16];byte[] tagSignature={'O','p','u','s','T','a','g','s'};
        System.arraycopy(tagSignature,0,tags,0,8);
        return new byte[][]{page(2,0,0,head),page(0,1,0,tags),page(0,2,960,new byte[]{1,2,3}),page(4,3,1920,new byte[]{4,5,6})};
    }
    private static byte[] page(int flags,int sequence,long granule,byte[] packet){
        byte[] page=new byte[28+packet.length];page[0]='O';page[1]='g';page[2]='g';page[3]='S';page[5]=(byte)flags;page[26]=1;page[27]=(byte)packet.length;
        for(int i=0;i<8;i++)page[6+i]=(byte)(granule>>>(8*i));
        page[14]=1;page[18]=(byte)sequence;System.arraycopy(packet,0,page,28,packet.length);
        int crc=0;
        for(int i=0;i<page.length;i++){crc^=(i>=22&&i<26?0:page[i]&255)<<24;for(int j=0;j<8;j++)crc=(crc<<1)^(crc<0?0x04c11db7:0);}
        for(int i=0;i<4;i++)page[22+i]=(byte)(crc>>>(8*i));return page;
    }
    private static byte[] join(byte[][] pages)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] page:pages)out.write(page);return out.toByteArray();}
    private static void warmupIsSameBackendAndPreemptible() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        Fake fake = new Fake() {
            @Override public JSONObject prewarm(JSONObject settings, CloudClient.Warmup budget) throws Exception {
                check(PRIVATE.equals(settings.getString("privateKey")), "startup uses selected backend/config in memory");
                entered.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!budget.isCancelled() && System.nanoTime() < until) Thread.yield();
                check(budget.isCancelled(), "actual pipeline run did not preempt startup");
                cancelled.countDown(); return new JSONObject().put("ok", false);
            }
        };
        VoicePipeline pipeline = new VoicePipeline(fake, new Clock());
        CloudClient.Warmup budget = new CloudClient.Warmup();
        Thread startup = new Thread(() -> pipeline.prewarm(uncheckedConfig(), budget));
        startup.start(); check(entered.await(1, TimeUnit.SECONDS), "startup backend not reached");
        VoicePipeline.Result result = pipeline.run(config(), input(44));
        check(result.summary.getBoolean("ok") && fake.transcriptions == 1 && fake.answers == 1 && fake.syntheses == 1,
                "startup blocked or duplicated actual pipeline");
        check(cancelled.await(1, TimeUnit.SECONDS), "startup did not observe cancellation");
        startup.join(1000); check(!startup.isAlive(), "startup leaked after cancellation");
    }
    private static JSONObject uncheckedConfig() {
        try { return config(); } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    public static void main(String[] args)throws Exception{
        happyAndSummary();rejectedInputs();deletionAndAsr();answerAndTtsErrors();interruptionAndProviderErrors();concurrentRun();
        streamingContract();streamingCancellationAndCleanup();combinedAudioAnswer();finalLocalTranscript();
        fixedLocalFailureFeedback();
        warmupIsSameBackendAndPreemptible();
        System.out.println("VoicePipelineHostTest: "+assertions+" assertions passed; artificial providers only, no sockets/API requests");
    }
}
