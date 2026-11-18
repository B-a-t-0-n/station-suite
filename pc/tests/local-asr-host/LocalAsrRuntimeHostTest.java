package local.station.voicebridge;

import android.content.Context;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;

/** Actual LocalAsrRuntime with deterministic in-memory codec/JNI/control boundaries. */
public final class LocalAsrRuntimeHostTest {
    private static int checks;
    private interface Condition { boolean ready(); }
    private static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);checks++;
    }
    private static void await(Condition condition,String message)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.ready()&&System.nanoTime()<until)Thread.yield();
        check(condition.ready(),message);
    }
    private static LegacyVoiceSession.Metadata metadata(int bytes) {return new LegacyVoiceSession.Metadata(bytes);}
    private static final class Fixture {
        final RuntimeVoiceHandler answers=new RuntimeVoiceHandler();
        final LocalAsrRuntime runtime=new LocalAsrRuntime(new Context(),answers,()->{});
        Fixture()throws Exception {await(()->runtime.isReady(),"model ready");}
        void close()throws Exception {runtime.close();await(()->runtime.canRelease(),"model released");}
    }
    private static void finalizedAndEarlyPrimaryRace()throws Exception {
        int loads=LocalSpeechRecognizer.loads.get(),releases=LocalSpeechRecognizer.releases.get();
        Fixture fixture=new Fixture();
        for(int pass=0;pass<2;pass++){
            LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
            check(fixture.runtime.offerStart(metadata(0),writer),"progressive start accepted");
            check(fixture.runtime.offerChunk(writer,new byte[8]),"selected chunk accepted");
            int expectedSessions=pass+1;
            await(()->LocalSpeechRecognizer.streams.get()>=expectedSessions,"worker opens session");
            // No matching END: neither an ASR final nor a cloud callback is permitted.
            Thread.sleep(20);
            check(fixture.answers.transcripts.get()==pass,"partial input never dispatched");
            check(fixture.runtime.offerEnd(metadata(8),writer),"matching END accepted");
            if(pass==0){
                await(()->fixture.runtime.snapshot().optLong("completed")==1,"ASR finishes before primary callback");
                check(writer.quiet.get()==0&&writer.open,"early ASR completion leaves answer writer open");
                fixture.runtime.commandReady(new byte[8],metadata(8),writer);
            }else fixture.runtime.commandReady(new byte[8],metadata(8),writer);
            int count=pass+1;
            await(()->fixture.answers.transcripts.get()==count,"one final transcript dispatched");
            await(()->fixture.runtime.snapshot().optLong("completed")==count,"turn finished");
            check(writer.quiet.get()==0&&writer.open,"primary/progressive finish race has no duplicate terminal callback");
            check(fixture.answers.audio.get()==0,"no cloud ASR fallback");
            writer.close();
        }
        check(LocalSpeechRecognizer.loads.get()==loads+1,"one warm model across turns");
        check(!fixture.runtime.snapshot().toString().contains("PRIVATE_TRANSCRIPT"),"no speech in runtime snapshot");
        fixture.close();
        check(LocalSpeechRecognizer.releases.get()==releases+1,"warm model freed once");
    }
    private static void boundedQueueAndStaleCancellation()throws Exception {
        Fixture fixture=new Fixture();
        LocalSpeechRecognizer.block();
        LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
        check(fixture.runtime.offerStart(metadata(0),writer),"overflow turn accepted");
        check(fixture.runtime.offerChunk(writer,new byte[2]),"first blocking chunk accepted");
        check(LocalSpeechRecognizer.entered.await(2,TimeUnit.SECONDS),"native accept blocked");
        int accepted=0;
        while(fixture.runtime.offerChunk(writer,new byte[2])){
            if(++accepted>128)throw new AssertionError("unbounded queue");
        }
        check(accepted==128,"queue is exactly bounded");
        fixture.runtime.cancel(writer,LoopbackVoiceServer.CancelReason.REJECTED);
        fixture.runtime.commandReady(new byte[2],metadata(2),writer);
        LocalSpeechRecognizer.release.countDown();
        await(()->fixture.runtime.snapshot().optLong("cancelled")>=1,"overflow cancellation drains native worker");
        check(fixture.answers.transcripts.get()==0&&fixture.answers.audio.get()==0,"overflow never dispatches or uploads");
        check(writer.quiet.get()==1,"overflow has one quiet completion");
        check(!fixture.runtime.offerChunk(writer,new byte[2]),"cancelled writer cannot enqueue");

        LocalSpeechRecognizer.block();
        LoopbackVoiceServer.ReplyWriter stale=new LoopbackVoiceServer.ReplyWriter();
        check(fixture.runtime.offerStart(metadata(0),stale),"stale turn accepted");
        check(fixture.runtime.offerChunk(stale,new byte[2]),"stale chunk accepted");
        check(LocalSpeechRecognizer.entered.await(2,TimeUnit.SECONDS),"stale accept blocked");
        stale.close();LocalSpeechRecognizer.release.countDown();
        await(()->fixture.runtime.snapshot().optLong("cancelled")>=2,"closed writer cancels turn");
        check(fixture.answers.transcripts.get()==0,"stale local text never dispatched");
        fixture.close();
    }
    private static void replacementAndCloseOwnership()throws Exception {
        Fixture fixture=new Fixture();LocalSpeechRecognizer.block();
        LoopbackVoiceServer.ReplyWriter old=new LoopbackVoiceServer.ReplyWriter();
        fixture.runtime.offerStart(metadata(0),old);fixture.runtime.offerChunk(old,new byte[2]);
        check(LocalSpeechRecognizer.entered.await(2,TimeUnit.SECONDS),"old worker in decoder");
        LoopbackVoiceServer.ReplyWriter next=new LoopbackVoiceServer.ReplyWriter();
        check(fixture.runtime.offerStart(metadata(0),next),"new turn supersedes old");
        check(fixture.runtime.offerChunk(next,new byte[4]),"new turn queued");
        check(fixture.runtime.offerEnd(metadata(4),next),"new turn finalized");
        fixture.runtime.commandReady(new byte[4],metadata(4),next);
        LocalSpeechRecognizer.release.countDown();
        await(()->fixture.answers.transcripts.get()==1,"replacement alone dispatches");
        check(fixture.answers.lastWriter==next,"old result cannot use newer writer");
        check(LocalSpeechRecognizer.overlap.get()==0,"recognizers never overlap warm model");
        await(()->fixture.runtime.snapshot().optLong("completed")==1,"replacement turn done");
        LocalSpeechRecognizer.block();
        LoopbackVoiceServer.ReplyWriter closing=new LoopbackVoiceServer.ReplyWriter();
        fixture.runtime.offerStart(metadata(0),closing);fixture.runtime.offerChunk(closing,new byte[2]);
        check(LocalSpeechRecognizer.entered.await(2,TimeUnit.SECONDS),"close worker in decoder");
        int released=LocalSpeechRecognizer.releases.get();long before=System.nanoTime();
        fixture.runtime.close();
        check(System.nanoTime()-before<TimeUnit.MILLISECONDS.toNanos(100),"runtime close only signals cancellation");
        check(!fixture.runtime.canRelease()&&LocalSpeechRecognizer.releases.get()==released,"model stays owned during native accept");
        LocalSpeechRecognizer.release.countDown();
        await(()->fixture.runtime.canRelease(),"native cleanup releases runtime ownership");
        check(LocalSpeechRecognizer.releases.get()==released+1,"model frees after native cleanup");
    }
    private static void genericSherpaSelection()throws Exception {
        int sherpaLoads=SherpaSpeechRecognizer.sherpaLoads.get();
        RuntimeVoiceHandler answers=new RuntimeVoiceHandler();
        LocalAsrRuntime runtime=new LocalAsrRuntime(new Context(),answers,()->{},"sherpa",2);
        try {
            await(()->runtime.isReady(),"generic Sherpa model ready");
            check(SherpaSpeechRecognizer.sherpaLoads.get()==sherpaLoads+1,"Sherpa adapter selected");
            check(SherpaSpeechRecognizer.lastThreads==2,"Sherpa thread count forwarded");
            check("local-sherpa-model".equals(SherpaSpeechRecognizer.lastDirectory),"Sherpa separate model directory");
            LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
            check(runtime.offerStart(metadata(0),writer),"Sherpa progressive start");
            check(runtime.offerChunk(writer,new byte[8]),"Sherpa selected input");
            check(runtime.offerEnd(metadata(8),writer),"Sherpa finalized input");
            runtime.commandReady(new byte[8],metadata(8),writer);
            await(()->answers.transcripts.get()==1,"generic final transcript forwarded");
            await(()->runtime.snapshot().optLong("completed")==1,"Sherpa turn finalized");
            check(answers.audio.get()==0&&writer.quiet.get()==0,"generic adapter no cloud ASR or duplicate completion");
            check("sherpa_zipformer_small_ru_int8".equals(runtime.snapshot().optString("engine")),"generic engine snapshot");
            check(!runtime.snapshot().optBoolean("cloudAsrFallback",true),"generic mode excludes cloud ASR fallback");
        } finally {runtime.close();await(()->runtime.canRelease(),"Sherpa model released");}
        for(String engine:new String[]{null,"", "remote"}) {
            try {new LocalAsrRuntime(new Context(),answers,()->{},engine,1);throw new AssertionError("invalid engine accepted");}
            catch(IllegalArgumentException error){check("LOCAL_ASR_CONFIG".equals(error.getMessage()),"fixed invalid-engine diagnostic");}
        }
        for(int threads:new int[]{0,3,8}) {
            try {new LocalAsrRuntime(new Context(),answers,()->{},"sherpa",threads);throw new AssertionError("invalid thread count accepted");}
            catch(IllegalArgumentException error){check("LOCAL_ASR_CONFIG".equals(error.getMessage()),"fixed invalid-thread diagnostic");}
        }
    }
    private static void passiveEndpointObservation()throws Exception {
        RuntimeVoiceHandler answers=new RuntimeVoiceHandler();
        LocalAsrRuntime runtime=new LocalAsrRuntime(new Context(),answers,()->{},"sherpa",1,true);
        try {
            await(()->runtime.isReady(),"observation runtime ready");
            check(SherpaSpeechRecognizer.lastObserveEndpoint,"observation forwarded to selected adapter");
            LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
            check(runtime.offerStart(metadata(0),writer),"observation starts selected stream");
            check(runtime.offerChunk(writer,new byte[8]),"observation PCM accepted");
            await(()->LocalSpeechRecognizer.endpointChecks.get()>0,"endpoint queried on worker");
            await(()->writer.partials.get()>0,"real interim hypothesis acknowledged before input end");
            Thread.sleep(20);
            check(answers.transcripts.get()==0 && writer.open && writer.quiet.get()==0,"endpoint alone never dispatches or closes input");
            check(runtime.offerChunk(writer,new byte[8]),"input continues after observed endpoint");
            check(runtime.offerEnd(metadata(16),writer),"matching native end still required");
            runtime.commandReady(new byte[16],metadata(16),writer);
            await(()->answers.transcripts.get()==1,"final dispatch after end only");
            await(()->runtime.snapshot().optLong("completed")==1,"observed turn completed");
            JSONObject status=runtime.snapshot();
            check(status.optBoolean("endpointObservation") && !status.optBoolean("earlyInputStopEnabled",true),"passive observation explicit and early stop disabled");
            check(status.optLong("lastEndpointMs",-1)>=0 && status.optLong("lastEndpointToInputEndMs",-1)>=0,"bounded endpoint timing recorded");
            check(!status.toString().contains("PRIVATE_TRANSCRIPT"),"endpoint snapshot contains no speech");
            check(status.optLong("lastFirstPartialMs",-1)>=0,"first speech acknowledgement timing recorded");
        } finally {runtime.close();await(()->runtime.canRelease(),"observed model released");}
    }
    private static void explicitServerEndpoint()throws Exception {
        RuntimeVoiceHandler answers=new RuntimeVoiceHandler();
        LocalAsrRuntime runtime=new LocalAsrRuntime(new Context(),answers,()->{},"sherpa",1,true,true);
        try {
            await(runtime::isReady,"server endpoint model ready");
            LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
            runtime.offerStart(metadata(0),writer);runtime.offerChunk(writer,new byte[8]);
            await(()->writer.serverEndpoints.get()>0,"only worker endpoint requests reader completion");
            check(writer.inputFinals.get()==0&&answers.transcripts.get()==0,"endpoint request cannot publish or execute a hypothesis");
            runtime.offerEnd(new LegacyVoiceSession.Metadata(8,-3),writer);
            await(()->answers.transcripts.get()==1,"server-completed validated input may answer");
            check(writer.inputFinals.get()==1,"actual final ASR published before dispatch");
            await(()->runtime.snapshot().optLong("completed")==1,"server turn cleanup");
            writer.close();
            LoopbackVoiceServer.ReplyWriter broken=new LoopbackVoiceServer.ReplyWriter();broken.failInputFinal=true;
            runtime.offerStart(metadata(0),broken);runtime.offerChunk(broken,new byte[8]);
            runtime.offerEnd(new LegacyVoiceSession.Metadata(8,-3),broken);
            await(()->runtime.snapshot().optLong("failed")==1,"failed final publication stops turn");
            check(answers.transcripts.get()==1,"failed ASR send never starts answer or action");
        } finally {runtime.close();await(runtime::canRelease,"server endpoint model released");}
        try {new LocalAsrRuntime(new Context(),answers,()->{},"sherpa",1,false,true);throw new AssertionError("endpoint without observation");}
        catch(IllegalArgumentException expected){checks++;}
        try {new LocalAsrRuntime(new Context(),answers,()->{},"vosk",1,true,true);throw new AssertionError("endpoint with unsupported model");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void musicFailureFeedback()throws Exception {
        LocalSpeechRecognizer.finalText="включи песню missing";
        FakeAgent.reset(FakeAgent.Mode.MUSIC_MISSING);
        Fixture fixture=new Fixture();
        try {
            LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
            fixture.runtime.offerStart(metadata(0),writer);
            fixture.runtime.offerChunk(writer,new byte[8]);
            fixture.runtime.offerEnd(metadata(8),writer);
            await(()->fixture.runtime.snapshot().optLong("completed")==1,"failed music command finalized");
            check(fixture.answers.feedbacks.get()==1&&fixture.answers.transcripts.get()==0,
                    "failed music uses fixed feedback without LLM question dispatch");
            fixture.runtime.commandReady(new byte[8],metadata(8),writer);
            check(writer.open&&writer.quiet.get()==0,"feedback retains reply writer after primary callback");
            JSONObject state=fixture.runtime.snapshot();
            check("PLAY_QUERY".equals(state.optString("lastLocalAction"))
                    &&"music_not_found".equals(state.optString("lastLocalResultCode"))
                    &&!state.optBoolean("lastLocalAccepted"),"failed playback is observable without speech");
            check(!state.toString().contains("PRIVATE_TRANSCRIPT"),"feedback diagnostics never contain transcript");
            writer.close();
        }finally{fixture.close();LocalSpeechRecognizer.finalText="PRIVATE_TRANSCRIPT";FakeAgent.reset(FakeAgent.Mode.NONE);}
    }
    private static void televisionChannelPrecedesMusicSearch()throws Exception {
        LocalSpeechRecognizer.finalText = "включи нтв";
        FakeAgent.reset(FakeAgent.Mode.MUSIC_MISSING);
        Fixture fixture = new Fixture();
        try {
            LoopbackVoiceServer.ReplyWriter writer = new LoopbackVoiceServer.ReplyWriter();
            check(fixture.runtime.offerStart(metadata(0), writer), "television channel turn admitted");
            check(fixture.runtime.offerChunk(writer, new byte[8]), "television channel audio admitted");
            check(fixture.runtime.offerEnd(metadata(8), writer), "television channel input ended");
            await(() -> fixture.runtime.snapshot().optLong("completed") == 1, "television channel routed");
            check(fixture.answers.transcripts.get() == 1 && fixture.answers.lastWriter == writer,
                    "television receives the finalized transcription");
            check(FakeAgent.opens.get() == 0 && fixture.answers.feedbacks.get() == 0,
                    "television tuning never searches for a song");
            writer.close();
        } finally {
            fixture.close();
            LocalSpeechRecognizer.finalText = "PRIVATE_TRANSCRIPT";
            FakeAgent.reset(FakeAgent.Mode.NONE);
        }
    }

    private static void mediaContextChangesRoute() throws Exception {
        java.util.concurrent.atomic.AtomicReference<MediaVoiceContext.Mode> screen =
                new java.util.concurrent.atomic.AtomicReference<>(MediaVoiceContext.Mode.TV);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(100);
        MediaVoiceContext context = new MediaVoiceContext(screen::get, now::get);
        check("video".equals(context.choose(false).videoType), "manual TV picks video search");
        context.selected(MediaVoiceContext.Mode.FILM);
        check("film".equals(context.choose(false).videoType), "new film selects film search during switching");
        screen.set(MediaVoiceContext.Mode.VIDEO);
        now.addAndGet(9000);
        check("film".equals(context.preferredVideoType()), "a generic player keeps the selected film type");
        context.selected(MediaVoiceContext.Mode.MUSIC);
        check(context.choose(false) == MediaVoiceContext.Mode.MUSIC, "music wins during player startup");
        now.addAndGet(9000);
        check(context.choose(true) == MediaVoiceContext.Mode.MUSIC, "confirmed music beats the old screen");
        screen.set(MediaVoiceContext.Mode.TV);
        check(context.choose(false) == MediaVoiceContext.Mode.TV, "TV wins when music is no longer playing");
        screen.set(MediaVoiceContext.Mode.NONE);
        now.addAndGet(TimeUnit.MINUTES.toMillis(21));
        check(context.choose(false) == MediaVoiceContext.Mode.NONE, "inactive remembered mode expires");
        check(context.choose(true) == MediaVoiceContext.Mode.MUSIC, "manual music without prior command is detected");
        screen.set(MediaVoiceContext.Mode.SERIES);
        check("series".equals(context.preferredVideoType()), "visible series selects series search");
    }

    private static void timerFinalizationAckAndOwnership()throws Exception {
        LocalSpeechRecognizer.finalText="Алиса поставь таймер на одну минуту";
        FakeAgent.reset(FakeAgent.Mode.TIMER_BLOCKED);
        Fixture fixture=new Fixture();
        try {
            LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
            check(fixture.runtime.offerStart(metadata(0),writer),"timer progressive start accepted");
            check(fixture.runtime.offerChunk(writer,new byte[8]),"timer chunk accepted");
            await(()->writer.partials.get()>0,"timer partial hypothesis published");
            check(FakeAgent.opens.get()==0&&fixture.answers.feedbacks.get()==0,
                    "partial recognition cannot create timer or confirm it");
            check(fixture.runtime.offerEnd(metadata(8),writer),"timer native final accepted");
            check(FakeAgent.entered.await(2,TimeUnit.SECONDS),"one finalized timer waits for Agent ACK");
            fixture.runtime.commandReady(new byte[8],metadata(8),writer);
            check(FakeAgent.opens.get()==1&&FakeAgent.acks.get()==0&&fixture.answers.feedbacks.get()==0,
                    "no confirmation before actual schedule response");
            check(fixture.answers.transcripts.get()==0&&fixture.answers.audio.get()==0,
                    "local timer never enters ordinary LLM/cloud ASR path");
            FakeAgent.release.countDown();
            await(()->fixture.runtime.snapshot().optLong("completed")==1,"timer command completed after ACK");
            check(FakeAgent.opens.get()==1&&FakeAgent.acks.get()==1&&fixture.answers.feedbacks.get()==1,
                    "exactly one schedule mutation and confirmation");
            check(fixture.answers.lastFeedback==VoicePipeline.LocalFeedback.TIMER_CREATED,
                    "successful ACK alone selects fixed timer confirmation");
            check(FakeAgent.lastDuration==60000L&&fixture.answers.lastWriter==writer&&writer.quiet.get()==0,
                    "ASR duration and reply writer retained for speech feedback");
            fixture.runtime.commandReady(new byte[8],metadata(8),writer);
            check(FakeAgent.opens.get()==1&&fixture.answers.feedbacks.get()==1,
                    "late primary callback cannot replay timer or confirmation");
            JSONObject status=fixture.runtime.snapshot();
            check("CREATE_TIMER".equals(status.optString("lastLocalAction"))&&status.optBoolean("lastLocalAccepted"),
                    "successful timer action visible without transcript");
            check(!status.toString().contains(LocalSpeechRecognizer.finalText),"timer status omits speech");
            writer.close();
        }finally{FakeAgent.release.countDown();fixture.close();LocalSpeechRecognizer.finalText="PRIVATE_TRANSCRIPT";FakeAgent.reset(FakeAgent.Mode.NONE);}
    }
    private static void failedTimerCannotClaimSuccess()throws Exception {
        for(FakeAgent.Mode mode:new FakeAgent.Mode[]{FakeAgent.Mode.TIMER_INVALID,FakeAgent.Mode.TIMER_LOST_ACK}){
            LocalSpeechRecognizer.finalText="поставь таймер на пять минут";
            FakeAgent.reset(mode);
            Fixture fixture=new Fixture();
            try {
                LoopbackVoiceServer.ReplyWriter writer=new LoopbackVoiceServer.ReplyWriter();
                fixture.runtime.offerStart(metadata(0),writer);fixture.runtime.offerChunk(writer,new byte[8]);
                fixture.runtime.offerEnd(metadata(8),writer);
                await(()->fixture.runtime.snapshot().optLong("completed")==1,"unconfirmed timer finalized");
                check(FakeAgent.opens.get()==1&&fixture.answers.feedbacks.get()==1,
                        "failed or ambiguous ACK has one attempt and one feedback");
                check(fixture.answers.lastFeedback==VoicePipeline.LocalFeedback.TIMER_UNAVAILABLE,
                        "unconfirmed timer never claims creation");
                check(fixture.answers.transcripts.get()==0&&fixture.answers.audio.get()==0,
                        "timer failure never falls through to LLM");
                check(!fixture.runtime.snapshot().optBoolean("lastLocalAccepted"),"unconfirmed state recorded honestly");
                writer.close();
            }finally{fixture.close();LocalSpeechRecognizer.finalText="PRIVATE_TRANSCRIPT";FakeAgent.reset(FakeAgent.Mode.NONE);}
        }
    }
    private static void staleTimerDoesNotActOrConfirm()throws Exception {
        LocalSpeechRecognizer.finalText="засеки пять минут";
        FakeAgent.reset(FakeAgent.Mode.TIMER_OK);
        Fixture fixture=new Fixture();
        final Fixture firstFixture=fixture;
        try {
            LocalSpeechRecognizer.block();
            LoopbackVoiceServer.ReplyWriter stale=new LoopbackVoiceServer.ReplyWriter();
            fixture.runtime.offerStart(metadata(0),stale);fixture.runtime.offerChunk(stale,new byte[8]);
            check(LocalSpeechRecognizer.entered.await(2,TimeUnit.SECONDS),"stale timer held inside recognizer");
            fixture.runtime.offerEnd(metadata(8),stale);stale.close();LocalSpeechRecognizer.release.countDown();
            await(()->firstFixture.runtime.snapshot().optLong("cancelled")>=1,"stale finalized timer discarded");
            check(FakeAgent.opens.get()==0&&fixture.answers.feedbacks.get()==0&&fixture.answers.transcripts.get()==0,
                    "closed writer before final ASR cannot mutate schedule or speak");
        }finally{LocalSpeechRecognizer.release.countDown();fixture.close();LocalSpeechRecognizer.finalText="PRIVATE_TRANSCRIPT";FakeAgent.reset(FakeAgent.Mode.NONE);}

        LocalSpeechRecognizer.finalText="таймер на 30 секунд";
        FakeAgent.reset(FakeAgent.Mode.TIMER_BLOCKED);
        fixture=new Fixture();
        final Fixture blockedFixture=fixture;
        try {
            LoopbackVoiceServer.ReplyWriter stale=new LoopbackVoiceServer.ReplyWriter();
            fixture.runtime.offerStart(metadata(0),stale);fixture.runtime.offerChunk(stale,new byte[8]);
            fixture.runtime.offerEnd(metadata(8),stale);
            check(FakeAgent.entered.await(2,TimeUnit.SECONDS),"timer response waiting before cancellation");
            stale.close();FakeAgent.release.countDown();
            await(()->blockedFixture.runtime.snapshot().optLong("completed")>=1
                    ||blockedFixture.runtime.snapshot().optLong("cancelled")>=1,"cancelled response worker drained");
            check(FakeAgent.opens.get()==1&&fixture.answers.feedbacks.get()==0&&fixture.answers.transcripts.get()==0,
                    "stale in-flight timer cannot retry, fall through or claim result");
        }finally{FakeAgent.release.countDown();fixture.close();LocalSpeechRecognizer.finalText="PRIVATE_TRANSCRIPT";FakeAgent.reset(FakeAgent.Mode.NONE);}
    }
    public static void main(String[] args)throws Exception {
        URL.setURLStreamHandlerFactory(protocol->"http".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url)throws IOException{
                throw new AssertionError("HTTP must select direct explicit proxy");
            }
            @Override protected URLConnection openConnection(URL url,Proxy proxy)throws IOException{
                return FakeAgent.open(url,proxy);
            }
        }:null);
        finalizedAndEarlyPrimaryRace();boundedQueueAndStaleCancellation();replacementAndCloseOwnership();
        genericSherpaSelection();
        passiveEndpointObservation();
        explicitServerEndpoint();
        musicFailureFeedback();
        mediaContextChangesRoute();
        televisionChannelPrecedesMusicSearch();
        timerFinalizationAckAndOwnership();
        failedTimerCannotClaimSuccess();
        staleTimerDoesNotActOrConfirm();
        System.out.println(checks+" local runtime assertions passed; fake JNI/codec, no sockets/API/device");
    }
}

final class RuntimeVoiceHandler {
    final AtomicInteger transcripts=new AtomicInteger(),audio=new AtomicInteger(),feedbacks=new AtomicInteger();
    volatile LoopbackVoiceServer.ReplyWriter lastWriter;
    volatile VoicePipeline.LocalFeedback lastFeedback;
    void recordLocal(String text, String code, boolean executed, long elapsedMs) { }
    void transcriptReady(String text,LegacyVoiceSession.Metadata metadata,LoopbackVoiceServer.ReplyWriter writer){
        if(!LocalSpeechRecognizer.finalText.equals(text))throw new AssertionError("wrong final text");
        lastWriter=writer;transcripts.incrementAndGet();
    }
    void commandFailed(String text,VoicePipeline.LocalFeedback feedback,LegacyVoiceSession.Metadata metadata,LoopbackVoiceServer.ReplyWriter writer){
        if(!LocalSpeechRecognizer.finalText.equals(text)||feedback!=VoicePipeline.LocalFeedback.MUSIC_NOT_FOUND)
            throw new AssertionError("wrong finalized feedback");
        lastWriter=writer;lastFeedback=feedback;feedbacks.incrementAndGet();
    }
    void commandFeedback(String text,VoicePipeline.LocalFeedback feedback,LegacyVoiceSession.Metadata metadata,LoopbackVoiceServer.ReplyWriter writer){
        if(!LocalSpeechRecognizer.finalText.equals(text)
                ||feedback!=VoicePipeline.LocalFeedback.TIMER_CREATED&&feedback!=VoicePipeline.LocalFeedback.TIMER_UNAVAILABLE
                &&feedback!=VoicePipeline.LocalFeedback.TIMER_NOT_RUNNING&&feedback!=VoicePipeline.LocalFeedback.TIMER_AMBIGUOUS
                &&feedback!=VoicePipeline.LocalFeedback.TIMER_STOP_UNAVAILABLE)
            throw new AssertionError("wrong timer feedback");
        if(feedback==VoicePipeline.LocalFeedback.TIMER_CREATED&&FakeAgent.acks.get()!=1)
            throw new AssertionError("timer confirmation before schedule ACK");
        lastWriter=writer;lastFeedback=feedback;feedbacks.incrementAndGet();
    }
}
final class VoicePipeline {enum LocalFeedback { MUSIC_NOT_FOUND, MUSIC_UNAVAILABLE, TIMER_CREATED, TIMER_UNAVAILABLE, TIMER_NOT_RUNNING, TIMER_AMBIGUOUS, TIMER_STOP_UNAVAILABLE }}
final class LegacyVoiceSession {
    static final int SERVER_ENDPOINT_REASON=-3;
    static final class Metadata {final int audioBytes,endReason;final long captureMillis=1;
        Metadata(int bytes){this(bytes,0);}Metadata(int bytes,int reason){audioBytes=bytes;endReason=reason;}}
}
final class NativeOggInput {static final int MAX_BYTES=512*1024;}
final class LoopbackVoiceServer {
    enum CancelReason {REJECTED}
    interface ProgressiveInput {
        boolean offerStart(LegacyVoiceSession.Metadata metadata,ReplyWriter writer);
        boolean offerChunk(ReplyWriter writer,byte[] selectedAudio);
        boolean offerEnd(LegacyVoiceSession.Metadata metadata,ReplyWriter writer);
        void cancel(ReplyWriter writer,CancelReason reason);
    }
    interface CommandHandler {void commandReady(byte[] audio,LegacyVoiceSession.Metadata metadata,ReplyWriter writer);}
    static final class ReplyWriter {
        volatile boolean open=true;final AtomicInteger quiet=new AtomicInteger();
        final AtomicInteger partials=new AtomicInteger();
        final AtomicInteger serverEndpoints=new AtomicInteger(),inputFinals=new AtomicInteger();
        boolean failInputFinal;
        boolean isOpen(){return open;}
        boolean requestPartialAsr(LegacyVoiceSession.Metadata metadata,String text){
            if(!open || !"PRIVATE_PARTIAL".equals(text))throw new AssertionError("invalid progress");
            partials.incrementAndGet();return true;
        }
        boolean requestServerEndpoint(LegacyVoiceSession.Metadata metadata){serverEndpoints.incrementAndGet();return open;}
        void finishInput(LegacyVoiceSession.Metadata metadata,String text)throws java.io.IOException{
            if(metadata.endReason!=-3 || text.isEmpty())throw new AssertionError("invalid server final");
            if(failInputFinal)throw new java.io.IOException("FIXTURE_SEND_FAILURE");
            inputFinals.incrementAndGet();
        }
        void abortInputFinalization(LegacyVoiceSession.Metadata metadata){if(inputFinals.get()==0)open=false;}
        void completeWithoutSpeech(LegacyVoiceSession.Metadata metadata){quiet.incrementAndGet();}
        void completeWithoutSpeech(LegacyVoiceSession.Metadata metadata,String text){quiet.incrementAndGet();}
        void close(){open=false;}
    }
}
class LocalSpeechRecognizer implements OnDeviceRecognizer {
    static final AtomicInteger loads=new AtomicInteger(),releases=new AtomicInteger(),streams=new AtomicInteger(),overlap=new AtomicInteger();
    static final AtomicInteger endpointChecks=new AtomicInteger();
    static volatile String finalText="PRIVATE_TRANSCRIPT";
    boolean observeEndpoint;
    static volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    private int active;
    LocalSpeechRecognizer(java.io.File model)throws RecognitionException{loads.incrementAndGet();}
    public long loadMillis(){return 1;}
    public Session newSession(){if(active++!=0)overlap.incrementAndGet();streams.incrementAndGet();return new Session();}
    public void close(){if(active!=0)throw new AssertionError("model freed with active recognizer");releases.incrementAndGet();}
    static void block(){entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    static final class RecognitionException extends Exception {
        private static final long serialVersionUID=1L;final String code;
        RecognitionException(String code){super(code);this.code=code;}
    }
    final class Session implements OnDeviceRecognizer.Session {
        volatile boolean cancelled;boolean closed;long bytes;
        public void acceptPcm16(byte[] pcm,int offset,int count)throws Exception{
            CountDownLatch gate=release;entered.countDown();
            if(!gate.await(3,TimeUnit.SECONDS))throw new AssertionError("gate timeout");
            if(cancelled)throw new RecognitionException("CANCELLED");bytes+=count;
        }
        public String finish()throws RecognitionException{
            if(cancelled)throw new RecognitionException("CANCELLED");close();return finalText;
        }
        public void cancel(){cancelled=true;}
        public long pcmBytes(){return bytes;}
        public long decodeMillis(){return 1;}
        public String previewText(){
            if(!Thread.currentThread().getName().equals("StationLocalASR"))throw new AssertionError("preview outside worker");
            return bytes>0&&!cancelled&&!closed?"PRIVATE_PARTIAL":"";
        }
        public boolean endpointReady(){if(!Thread.currentThread().getName().equals("StationLocalASR"))throw new AssertionError("endpoint outside worker");endpointChecks.incrementAndGet();return observeEndpoint&&bytes>0&&!cancelled&&!closed;}
        public void close(){if(!closed){closed=true;active--;}}
    }
}
final class SherpaSpeechRecognizer extends LocalSpeechRecognizer {
    static final AtomicInteger sherpaLoads=new AtomicInteger();
    static int lastThreads;static String lastDirectory;static boolean lastObserveEndpoint;
    SherpaSpeechRecognizer(java.io.File model,int threads)throws RecognitionException {
        this(model,threads,false);
    }
    SherpaSpeechRecognizer(java.io.File model,int threads,boolean observe)throws RecognitionException {
        super(model);sherpaLoads.incrementAndGet();lastThreads=threads;lastDirectory=model.getName();lastObserveEndpoint=observe;observeEndpoint=observe;
    }
}
final class AndroidOpusDecoder {
    interface PcmSink {void accept(byte[] pcm,int offset,int count)throws Exception;boolean cancelled();}
    final PcmSink sink;AndroidOpusDecoder(PcmSink sink){this.sink=sink;}
}
final class StreamingOpusInput implements AutoCloseable {
    final AndroidOpusDecoder decoder;int bytes;
    StreamingOpusInput(AndroidOpusDecoder decoder){this.decoder=decoder;}
    void accept(byte[] data,int offset,int count)throws Exception{bytes+=count;decoder.sink.accept(data,offset,count);}
    void finish(){}int inputBytes(){return bytes;}public void close(){}
    void poll(){}
    int parsedBytes(){return bytes;}int pages(){return 1;}int audioPackets(){return 1;}
    int pendingPageBytes(){return 0;}int expectedPageBytes(){return -1;}boolean completeBoundary(){return bytes>0;}
}
/** Process-local URL handler: all actuator HTTP is memory only, and unexpected paths fail. */
final class FakeAgent {
    enum Mode { NONE, MUSIC_MISSING, TIMER_OK, TIMER_BLOCKED, TIMER_INVALID, TIMER_LOST_ACK }
    static volatile Mode mode=Mode.NONE;
    static final AtomicInteger opens=new AtomicInteger(),acks=new AtomicInteger();
    static volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    static volatile long lastDuration;
    static void reset(Mode selected){
        mode=selected;opens.set(0);acks.set(0);lastDuration=0;
        entered=new CountDownLatch(selected==Mode.TIMER_BLOCKED?1:0);
        release=new CountDownLatch(selected==Mode.TIMER_BLOCKED?1:0);
    }
    static HttpURLConnection open(URL url,Proxy proxy)throws IOException {
        if(proxy!=Proxy.NO_PROXY||!"127.0.0.1".equals(url.getHost())||url.getPort()!=18080)
            throw new AssertionError("nonlocal actuator request");
        Mode selected=mode;
        String response;
        if(selected==Mode.MUSIC_MISSING&&url.getPath().equals("/api/search"))response="{\"tracks\":[]}";
        else if(selected!=Mode.NONE&&selected!=Mode.MUSIC_MISSING&&url.getPath().equals("/api/schedules")){
            try {
                String query=url.getQuery();
                if(query==null||!query.startsWith("request="))throw new AssertionError("missing schedule request");
                JSONObject payload=new JSONObject(URLDecoder.decode(query.substring(8),"UTF-8"));
                if(!"create_timer".equals(payload.getString("op"))||payload.length()!=3
                        ||!payload.getString("requestId").matches("[0-9a-f-]{36}"))
                    throw new AssertionError("unexpected schedule operation");
                lastDuration=payload.getLong("durationMs");
                if(lastDuration<5000||lastDuration>86400000)throw new AssertionError("unsafe duration");
                String id="12345678-1234-1234-1234-123456789abc";
                response=selected==Mode.TIMER_INVALID?"{\"ok\":true}":new JSONObject().put("ok",true)
                        .put("id",id).put("entries",new org.json.JSONArray().put(new JSONObject().put("id",id)
                        .put("type","timer").put("enabled",true).put("outcome","scheduled")
                        .put("remainingMs",lastDuration))).toString();
            }catch(org.json.JSONException error){throw new AssertionError(error);}
        }else throw new AssertionError("unexpected actuator URL: "+url.getPath());
        opens.incrementAndGet();
        final byte[] body=response.getBytes(StandardCharsets.UTF_8);
        return new HttpURLConnection(url){
            volatile boolean disconnected,outputClosed;
            public void connect(){}public boolean usingProxy(){return false;}
            public void disconnect(){disconnected=true;}
            public OutputStream getOutputStream(){return new ByteArrayOutputStream(){public void close(){outputClosed=true;}};}
            public int getResponseCode()throws IOException {
                if(selected==Mode.MUSIC_MISSING){if(!"GET".equals(getRequestMethod()))throw new AssertionError("music lookup mutation");}
                else if(!"POST".equals(getRequestMethod())||!outputClosed)throw new AssertionError("timer POST before body closure");
                if(selected==Mode.TIMER_BLOCKED){
                    entered.countDown();
                    try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("timer ACK gate timeout");}
                    catch(InterruptedException error){throw new IOException("INTERRUPTED");}
                }
                if(disconnected||selected==Mode.TIMER_LOST_ACK)throw new IOException("ACK_NOT_AVAILABLE");
                return 200;
            }
            public long getContentLengthLong(){return body.length;}
            public InputStream getInputStream(){return new ByteArrayInputStream(body){
                public void close(){if(selected!=Mode.MUSIC_MISSING)acks.incrementAndGet();}
            };}
        };
    }
}
