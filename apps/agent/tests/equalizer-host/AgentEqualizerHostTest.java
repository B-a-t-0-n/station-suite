package local.station.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual controller, artificial sessions/engine/persistence; no Android, sound or network. */
public final class AgentEqualizerHostTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static JSONObject request(String op) throws Exception { return new JSONObject().put("op", op); }
    private static final class Memory implements AgentEqualizer.Store {
        String value; boolean commit = true; int writes;
        @Override public String load() { return value; }
        @Override public boolean save(String next) { writes++; if (!commit) return false; value = next; return true; }
    }
    private static final class Effect implements AgentEqualizer.Engine {
        int[] centers = {60000, 230000, 910500, 3600000, 14000000};
        int[] gains = new int[5]; int minimum=-1200, maximum=1200; boolean on, released, reject, lie;
        int sets, enables;
        @Override public int bandCount() { return centers.length; }
        @Override public int minLevelMb() { return minimum; }
        @Override public int maxLevelMb() { return maximum; }
        @Override public int centerMilliHz(int band) { return centers[band]; }
        @Override public void setLevelMb(int band, int gain) { check(!released, "effect used after release"); if (reject) throw new IllegalStateException(); gains[band]=gain;sets++; }
        @Override public int levelMb(int band) { return lie ? 999 : gains[band]; }
        @Override public void setEnabled(boolean enabled) { check(!released,"released enabled"); if(reject)throw new IllegalStateException();on=enabled;enables++; }
        @Override public boolean enabled() { return on; }
        @Override public void release() { check(!released,"double release");released=true; }
    }
    private static final class Fake implements AgentEqualizer.Backend {
        final List<Effect> probes=new ArrayList<>(), attached=new ArrayList<>();
        Effect next; int session=-1;boolean fail;
        private Effect make() { if(fail)throw new UnsupportedOperationException();Effect e=next==null?new Effect():next;next=null;return e; }
        @Override public AgentEqualizer.Engine probe() { Effect e=make();probes.add(e);return e; }
        @Override public AgentEqualizer.Engine attach(int sessionId) {check(sessionId>0,"never mix/global session");session=sessionId;Effect e=make();attached.add(e);return e; }
    }
    private static void error(AgentEqualizer eq, JSONObject command, String code) {
        JSONObject response=eq.execute(command);
        check(!response.optBoolean("ok")&&code.equals(response.optString("code")),"fixed failure "+code);
        check("agent_player".equals(response.optString("scope"))&&response.optJSONArray("bands")!=null,"stable response shape");
    }
    private static JSONObject select(String preset) throws Exception { return request("set_preset").put("preset",preset); }
    private static int[] gains(JSONObject response) throws Exception {
        JSONArray bands=response.getJSONArray("bands");int[] result=new int[bands.length()];
        for(int i=0;i<result.length;i++)result[i]=bands.getJSONObject(i).getInt("levelMb");
        return result;
    }
    private static void presets() throws Exception {
        String[] ids={"normal","bass","vocal","soft","energetic","cinema"};
        String[] names={"Обычный","Больше баса","Вокал","Мягкий","Энергичный","Кино"};
        int[][] anchors={{0,0,0,0,0},{400,250,0,-100,-150},{-200,-100,250,200,0},
                {100,50,0,-150,-300},{250,100,-100,150,250},{300,100,0,150,100}};
        Fake unprobed=new Fake();Memory untouched=new Memory();AgentEqualizer strict=new AgentEqualizer(unprobed,untouched);
        error(strict,select("missing"),"invalid_preset");
        error(strict,select("BASS"),"invalid_preset");
        error(strict,select("movie"),"invalid_preset");
        for(JSONObject invalid:new JSONObject[]{request("set_preset"),request("set_preset").put("preset",1),
                request("set_preset").put("presetId","bass"),select("bass").put("enabled",true)})
            error(strict,invalid,"invalid_request");
        check(unprobed.probes.isEmpty()&&unprobed.attached.isEmpty()&&untouched.writes==0,"unknown preset and schema rejected before any engine or persistence mutation");
        JSONObject catalog=strict.execute(select("missing"),900);
        check(unprobed.attached.isEmpty()&&catalog.getInt("playerSessionId")==0,"unknown preset cannot attach newly reported current player");
        JSONArray advertised=catalog.getJSONArray("presets");
        check(advertised.length()==6&&"custom".equals(catalog.getString("selectedPreset")),"catalog advertised even before capabilities known");
        for(int i=0;i<ids.length;i++) {
            check(ids[i].equals(advertised.getJSONObject(i).getString("id"))&&names[i].equals(advertised.getJSONObject(i).getString("name"))
                    &&!advertised.getJSONObject(i).getString("description").isEmpty(),"stable preset catalog with readable descriptions");
            Fake fake=new Fake();Effect shape=new Effect();shape.centers[2]=910000;fake.next=shape;
            Memory store=new Memory();AgentEqualizer eq=new AgentEqualizer(fake,store);
            JSONObject chosen=eq.execute(select(ids[i]));
            check(chosen.getBoolean("ok")&&ids[i].equals(chosen.getString("selectedPreset"))
                    &&Arrays.equals(anchors[i],gains(chosen))&&chosen.getBoolean("enabled")==!ids[i].equals("normal"),"preset maps exactly at all five anchors and sets enabled");
            check(store.writes==1&&new JSONObject(store.value).getInt("version")==1
                    &&new JSONObject(store.value).getJSONArray("levels").length()==5,"one full-curve commit using existing persistence version");
            Fake fresh=new Fake();Effect sameShape=new Effect();sameShape.centers[2]=910000;fresh.next=sameShape;
            AgentEqualizer restored=new AgentEqualizer(fresh,store);
            check(ids[i].equals(restored.execute(request("get")).getString("selectedPreset"))&&store.writes==1,"preset derived from persisted gains and enable after recreation");
            eq.execute(select(ids[i]));check(store.writes==1,"reselecting same preset does not rewrite settings");
        }
        Fake spread=new Fake();Effect spreadShape=new Effect();
        spreadShape.centers=new int[]{30000,60000,(int)Math.round(Math.sqrt(60*230)*1000),230000,910000,3600000,14000000,28000000};
        spreadShape.gains=new int[8];spread.next=spreadShape;AgentEqualizer logarithmic=new AgentEqualizer(spread,new Memory());
        check(Arrays.equals(new int[]{400,400,325,250,0,-100,-150,-150},gains(logarithmic.execute(select("bass")))),"log-frequency midpoint interpolation and endpoint extension for eight dynamic bands");
        Fake narrow=new Fake();Effect limited=new Effect();limited.minimum=-120;limited.maximum=200;
        limited.centers=new int[]{30000,117473,28000000};limited.gains=new int[3];narrow.next=limited;
        AgentEqualizer clipped=new AgentEqualizer(narrow,new Memory());JSONObject clamped=clipped.execute(select("bass"));
        check(Arrays.equals(new int[]{200,200,-120},gains(clamped))&&"bass".equals(clamped.getString("selectedPreset")),"interpolated result clamped to actual three-band engine range");
        Fake fake=new Fake();Memory store=new Memory();AgentEqualizer live=new AgentEqualizer(fake,store);
        live.attach(301);Effect engine=fake.attached.get(0);int sets=engine.sets,enables=engine.enables;
        JSONObject bass=live.execute(select("bass"),301);
        check(bass.getBoolean("ok")&&bass.getBoolean("effectiveEnabled")&&engine.sets==sets+5&&engine.enables==enables+2
                &&store.writes==1,"one atomic live preset applies entire curve disabled then enables and commits once");
        int[] prior=engine.gains.clone();String saved=store.value;int attempts=bass.getInt("attachAttempts");sets=engine.sets;
        JSONObject unknown=live.execute(select("unknown"),302);
        check("invalid_preset".equals(unknown.getString("code"))&&engine.sets==sets&&!engine.released
                &&unknown.getInt("attachAttempts")==attempts&&unknown.getInt("playerSessionId")==301&&store.value.equals(saved),"unknown choice does not touch live effect even with mismatched reported session");
        store.commit=false;JSONObject rollback=live.execute(select("vocal"),301);
        check("storage_unavailable".equals(rollback.getString("code"))&&Arrays.equals(prior,engine.gains)&&engine.on
                &&"bass".equals(rollback.getString("selectedPreset"))&&store.value.equals(saved),"failed preset commit restores entire live prior curve and selected choice");
        store.commit=true;live.execute(request("set_band").put("band",1).put("levelMb",111),301);
        saved=store.value;int writes=store.writes;
        check("custom".equals(live.execute(request("get"),301).getString("selectedPreset"))&&store.writes==writes,"old custom curve retained and not silently replaced by available presets");
        AgentEqualizer restarted=new AgentEqualizer(new Fake(),store);
        check("custom".equals(restarted.execute(request("get")).getString("selectedPreset"))&&store.value.equals(saved),"custom persistence preserved across service recreation");
        engine.reject=true;JSONObject rejected=live.execute(select("cinema"),301);
        check("effect_not_attached".equals(rejected.getString("code"))&&!rejected.getBoolean("effectiveEnabled")
                &&engine.released&&store.writes==writes&&store.value.equals(saved)&&"custom".equals(rejected.getString("selectedPreset")),"preset effect failure releases effect without committing partial curve");
        JSONObject normal=live.execute(select("normal"),301);
        check(normal.getBoolean("ok")&&normal.getBoolean("attached")&&!normal.getBoolean("enabled")&&!normal.getBoolean("effectiveEnabled")
                &&"normal".equals(normal.getString("selectedPreset"))&&Arrays.equals(new int[5],fake.attached.get(1).gains),"normal recovers session and restores unprocessed flat disabled state");
    }
    public static void main(String[] ignored) throws Exception {
        Fake fake=new Fake();Memory store=new Memory();AgentEqualizer eq=new AgentEqualizer(fake,store);
        check(fake.probes.isEmpty()&&fake.attached.isEmpty(),"construction inert except saved text load");
        JSONObject initial=eq.execute(request("get"));
        check(initial.getBoolean("ok")&&initial.getBoolean("available")&&!initial.getBoolean("attached")&&!initial.getBoolean("enabled"),"probe exposes availability without attachment");
        check(initial.getInt("minLevelMb")==-1200&&initial.getInt("maxLevelMb")==1200,"range comes from engine");
        JSONArray bands=initial.getJSONArray("bands");
        check(bands.length()==5&&bands.getJSONObject(2).getDouble("centerHz")==910.5,"dynamic frequencies in Hz, fractional precision retained");
        check(fake.probes.size()==1&&fake.probes.get(0).released&&fake.probes.get(0).sets==0&&fake.probes.get(0).enables==0,"probe reads and releases, no configuration calls");
        eq.execute(request("get"));check(fake.probes.size()==1&&store.writes==0,"get reuses capabilities and never saves");
        eq.execute(request("set_band").put("band",2).put("levelMb",350));
        eq.execute(request("set_enabled").put("enabled",true));
        check(store.writes==2&&new JSONObject(store.value).getBoolean("enabled"),"settings saved while player absent");
        check(fake.attached.isEmpty(),"set without player does not create fake playback");
        eq.attach(71);Effect first=fake.attached.get(0);
        check(fake.session==71&&first.gains[2]==350&&first.on&&eq.execute(request("get")).getBoolean("attached"),"saved curve applies to exact owned player");
        eq.execute(request("set_band").put("band",0).put("levelMb",-1200));
        check(first.gains[0]==-1200&&first.on,"live curve applied with enabled state preserved");
        eq.detach();check(first.released&&!eq.execute(request("get")).getBoolean("attached"),"player release also releases effect");
        eq.attach(72);Effect second=fake.attached.get(1);
        check(second.on&&second.gains[0]==-1200&&second.gains[2]==350,"new track session restores saved curve");
        AgentEqualizer recreated=new AgentEqualizer(new Fake(),store);
        JSONObject saved=recreated.execute(request("get"));
        check(saved.getBoolean("enabled")&&saved.getJSONArray("bands").getJSONObject(0).getInt("levelMb")==-1200,"service recreation restores persisted desired state");
        int before=store.writes;
        for(JSONObject command:new JSONObject[]{new JSONObject(),request("shell"),request("get").put("extra",1),request("set_enabled"),
                request("set_enabled").put("enabled","true"),request("set_band").put("band","1").put("levelMb",1),
                request("set_band").put("band",1).put("levelMb",0.5),request("set_band").put("band",1.0).put("levelMb",0)})
            error(eq,command,"invalid_request");
        error(eq,request("set_band").put("band",-1).put("levelMb",0),"invalid_band");
        error(eq,request("set_band").put("band",5).put("levelMb",0),"invalid_band");
        error(eq,request("set_band").put("band",4294967296L).put("levelMb",0),"invalid_band");
        error(eq,request("set_band").put("band",1).put("levelMb",-1201),"invalid_level");
        error(eq,request("set_band").put("band",1).put("levelMb",1201),"invalid_level");
        check(store.writes==before&&second.gains[2]==350&&second.on,"invalid commands do not persist/change engine");
        store.commit=false;
        error(eq,request("set_band").put("band",2).put("levelMb",600),"storage_unavailable");
        check(second.gains[2]==350&&second.on&&eq.execute(request("get")).getJSONArray("bands").getJSONObject(2).getInt("levelMb")==350,"failed commit rolls live curve and configured state back");
        store.commit=true;eq.execute(request("reset"));
        check(!second.on&&Arrays.equals(second.gains,new int[5])&&!new JSONObject(store.value).getBoolean("enabled"),"reset is flat and disabled, persisted");
        second.reject=true;
        error(eq,request("set_enabled").put("enabled",true),"effect_not_attached");
        check(second.released&&!eq.execute(request("get")).getBoolean("attached"),"broken live effect detached, probe recovery safe");
        Fake unsupported=new Fake();unsupported.fail=true;AgentEqualizer no=new AgentEqualizer(unsupported,new Memory());
        error(no,request("get"),"equalizer_unavailable");
        JSONObject unavailable=no.execute(request("get"));
        check(!unavailable.getBoolean("available")&&unavailable.getJSONArray("bands").length()==0&&unavailable.getInt("maxLevelMb")==0,"unavailable never invents bands or range");
        Fake changed=new Fake();Effect changedShape=new Effect();changedShape.centers=new int[]{100000,1000000,10000000};changedShape.gains=new int[3];changed.next=changedShape;
        AgentEqualizer incompatible=new AgentEqualizer(changed,store);JSONObject newShape=incompatible.execute(request("get"));
        check(!newShape.getBoolean("enabled")&&newShape.getJSONArray("bands").length()==3&&newShape.getJSONArray("bands").getJSONObject(0).getInt("levelMb")==0,"incompatible saved curve never maps onto different bands");
        int writes=store.writes;incompatible.execute(request("reset"));
        check(store.writes==writes+1,"explicit reset replaces incompatible saved signature even already flat");
        Fake malformed=new Fake();Effect bad=new Effect();bad.centers=new int[33];malformed.next=bad;
        AgentEqualizer badController=new AgentEqualizer(malformed,new Memory());error(badController,request("get"),"equalizer_unavailable");
        check(bad.released,"invalid probe released");
        Fake zero=new Fake();AgentEqualizer guarded=new AgentEqualizer(zero,new Memory());guarded.attach(0);guarded.attach(-1);
        check(zero.attached.isEmpty(),"invalid/global session never reaches backend");
        Fake lie=new Fake();AgentEqualizer verified=new AgentEqualizer(lie,new Memory());Effect falseRead=new Effect();falseRead.lie=true;lie.next=falseRead;verified.attach(73);
        check(falseRead.released&&!verified.execute(request("get")).getBoolean("attached"),"effect readback required before attachment success");
        Fake recoverable=new Fake();Memory recoveredStore=new Memory();AgentEqualizer recovery=new AgentEqualizer(recoverable,recoveredStore);
        recoverable.fail=true;recovery.attach(201);
        JSONObject lost=recovery.execute(request("get"),201);
        check(!lost.getBoolean("ok")&&!lost.getBoolean("attached")&&!lost.getBoolean("effectiveEnabled")
                &&lost.getInt("playerSessionId")==201&&lost.getInt("attachedSessionId")==0,"failed current-session attachment is visible");
        check("create".equals(lost.getString("diagnosticPhase"))&&"unsupported".equals(lost.getString("diagnosticReason")),"sanitized creation phase and reason retained");
        recoverable.fail=false;JSONObject probed=recovery.execute(request("get"),201);
        check(probed.getBoolean("available")&&!probed.getBoolean("ok")&&!probed.getBoolean("attached")
                &&"effect_not_attached".equals(probed.getString("code")),"successful disposable probe never masks failed playing-session attachment");
        check(probed.getInt("attachAttempts")==1&&recoverable.attached.isEmpty(),"GET does not repeatedly recreate an effect or restart current music");
        JSONObject retried=recovery.execute(request("set_band").put("band",1).put("levelMb",450),201);
        check(retried.getBoolean("ok")&&retried.getBoolean("attached")&&retried.getInt("attachedSessionId")==201
                &&retried.getInt("attachAttempts")==2,"explicit edit retries effect on exact current player session");
        check(recoverable.attached.size()==1&&recoverable.attached.get(0).gains[1]==450
                &&recoverable.session==201&&recoveredStore.writes==1,"retry reapplies then saves intended curve without a replacement player");
        Effect changedLive=recoverable.attached.get(0);changedLive.gains[1]=0;
        JSONObject mismatch=recovery.execute(request("get"),201);
        check(!mismatch.getBoolean("ok")&&!mismatch.getBoolean("attached")&&changedLive.released
                &&"band_readback".equals(mismatch.getString("diagnosticPhase"))&&mismatch.getInt("failedBand")==1,"readback mismatch detaches rather than showing saved settings as applied");
        JSONObject recovered=recovery.execute(request("set_enabled").put("enabled",true),201);
        check(recovered.getBoolean("ok")&&recovered.getBoolean("attached")&&recovered.getBoolean("effectiveEnabled")
                &&recoverable.attached.get(1).gains[1]==450,"explicit next edit recovers failed session and retained saved curve");
        int attempts=recovered.getInt("attachAttempts");
        recovery.execute(request("get"),201);
        check(recovery.execute(request("get"),201).getInt("attachAttempts")==attempts,"healthy GET rechecks but does not recreate attached effect");
        Effect staleDuringEdit=recoverable.attached.get(1);staleDuringEdit.gains[1]=0;
        JSONObject sameEdit=recovery.execute(request("set_band").put("band",2).put("levelMb",-350),201);
        check(sameEdit.getBoolean("ok")&&sameEdit.getBoolean("effectiveEnabled")&&staleDuringEdit.released
                &&sameEdit.getInt("attachAttempts")==attempts+1&&recoverable.attached.get(2).gains[1]==450
                &&recoverable.attached.get(2).gains[2]==-350,"explicit edit discovering a stale effect retries once within same command");
        recovery.execute(request("get"),0);
        check(recoverable.attached.get(2).released&&recovery.execute(request("get")).getInt("playerSessionId")==0,"loss of prepared player releases effect and clears session ownership");
        presets();
        System.out.println(assertions+" equalizer assertions passed; artificial engines, no playback/network/device");
    }
}
