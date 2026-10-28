package com.phnem.vetro.titlematchbench;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import com.example.myapplication.domain.titleidentity.*;

/** Isolated offline benchmark. No collection access or network permission. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final String runId = getIntent().getStringExtra("titlematch_run_id");
        TextView text = new TextView(this); text.setText("TitleMatch benchmark running"); setContentView(text);
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                report.put("run_started_at_epoch_ms",System.currentTimeMillis());
                report.put("run_id",runId == null ? "manual" : runId);
                report.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                report.put("sdk", Build.VERSION.SDK_INT);
                report.put("hardware", Build.HARDWARE);
                JSONObject fixture = new JSONObject(new String(readAsset("vectors.json"), StandardCharsets.UTF_8));
                JSONArray vectors = fixture.getJSONArray("vectors");
                byte[] forestBytes = readAsset("model.vtmf");
                byte[] neuralBytes = readAsset("charcnn.int8.tflite");
                if (!hex(forestBytes).equals(fixture.getString("forest_sha256")) || !hex(neuralBytes).equals(fixture.getString("neural_sha256"))) throw new IllegalStateException("Asset checksum mismatch");
                long started = System.nanoTime(); Forest forest = new Forest(forestBytes);
                report.put("forest_load_ms", (System.nanoTime()-started)/1e6);
                double error=0; float[][] features=new float[vectors.length()][];
                for(int i=0;i<vectors.length();i++) {
                    JSONObject v=vectors.getJSONObject(i); features[i]=floats(v.getJSONArray("features"));
                    error=Math.max(error,Math.abs(forest.predict(features[i])-v.getDouble("forest_raw_probability")));
                }
                report.put("forest_max_probability_error",error);
                double[] times=new double[1000];
                for(int i=-50;i<times.length;i++) { long t=System.nanoTime(); forest.predict(features[Math.floorMod(i,features.length)]); if(i>=0)times[i]=(System.nanoTime()-t)/1e6; }
                report.put("forest",timing(times));
                ByteBuffer model=ByteBuffer.allocateDirect(neuralBytes.length).order(ByteOrder.nativeOrder());model.put(neuralBytes);model.rewind();
                started=System.nanoTime();
                try(Interpreter interpreter=new Interpreter(model,new Interpreter.Options().setNumThreads(2))) {
                    interpreter.allocateTensors();report.put("neural_load_ms",(System.nanoTime()-started)/1e6);
                    Object[][] inputs=new Object[vectors.length()][];Map<Integer,Object> outputs=new HashMap<>();
                    ByteBuffer out=ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());outputs.put(0,out);
                    for(int i=0;i<vectors.length();i++) {
                        JSONObject vector=vectors.getJSONObject(i);inputs[i]=new Object[interpreter.getInputTensorCount()];
                        for(int j=0;j<inputs[i].length;j++) {
                            Tensor tensor=interpreter.getInputTensor(j);String name=tensor.name();ByteBuffer buffer;
                            if(name.contains("features")) {
                                buffer=ByteBuffer.allocateDirect(features[i].length).order(ByteOrder.nativeOrder());
                                float scale=tensor.quantizationParams().getScale();int zero=tensor.quantizationParams().getZeroPoint();
                                for(float value:features[i])buffer.put((byte)Math.max(-128,Math.min(127,Math.rint(value/scale)+zero)));
                            } else {
                                JSONArray values=vector.getJSONArray(name.contains("title_a")?"title_a":"title_b");
                                buffer=ByteBuffer.allocateDirect(4*values.length()).order(ByteOrder.nativeOrder());
                                for(int k=0;k<values.length();k++)buffer.putInt(values.getInt(k));
                            }
                            buffer.rewind();inputs[i][j]=buffer;
                        }
                    }
                    error=0;Tensor tensor=interpreter.getOutputTensor(0);
                    for(int i=0;i<vectors.length();i++) {
                        rewind(inputs[i]);out.rewind();interpreter.runForMultipleInputsOutputs(inputs[i],outputs);
                        double logit=(out.get(0)-tensor.quantizationParams().getZeroPoint())*tensor.quantizationParams().getScale();
                        double p=1/(1+Math.exp(-logit));error=Math.max(error,Math.abs(p-vectors.getJSONObject(i).getDouble("neural_raw_probability")));
                    }
                    report.put("neural_max_probability_error",error);
                    for(int i=-50;i<times.length;i++) { int j=Math.floorMod(i,inputs.length);rewind(inputs[j]);out.rewind();long t=System.nanoTime();interpreter.runForMultipleInputsOutputs(inputs[j],outputs);if(i>=0)times[i]=(System.nanoTime()-t)/1e6; }
                    report.put("neural",timing(times));
                }
                started=System.nanoTime();
                TitleMatchRuntime runtime=TitleMatchPack.INSTANCE.loadVerified(getAssets(),"titlematch/v1");
                if(runtime==null)throw new IllegalStateException("Checksummed model/data pack failed to load");
                report.put("pack_load_ms",(System.nanoTime()-started)/1e6);report.put("model_version",runtime.getVersion());
                JSONArray golden=new JSONObject(new String(readAsset("feature-golden.json"),StandardCharsets.UTF_8)).getJSONArray("vectors");
                double featureError=0,coreError=0;
                for(int i=0;i<golden.length();i++) {
                    JSONObject v=golden.getJSONObject(i);JSONArray expected=v.getJSONArray("features");
                    float[] actual=TitleMatchFeatures.INSTANCE.compute(v.getString("a"),v.getString("b"),v.getString("kind_a"),v.getString("kind_b"),v.isNull("year_a")?null:v.getInt("year_a"),v.isNull("year_b")?null:v.getInt("year_b"));
                    for(int j=0;j<actual.length;j++)featureError=Math.max(featureError,Math.abs(actual[j]-expected.getDouble(j)));
                    coreError=Math.max(coreError,Math.abs(runtime.getForest().rawProbability(actual)-v.getDouble("raw_probability")));
                }
                report.put("kotlin_feature_max_error",featureError);report.put("kotlin_forest_max_error",coreError);
                JSONArray cases=new JSONObject(new String(readAsset("challenge.json"),StandardCharsets.UTF_8)).getJSONArray("cases");
                int falseSame=0,unsafeActions=0,verifiedPositives=0,positiveCases=0;
                for(int i=0;i<cases.length();i++) {
                    JSONObject c=cases.getJSONObject(i);TitleKind ka=TitleKind.valueOf(c.getString("kind"));TitleKind kb=TitleKind.valueOf(c.optString("kind_b",ka.name()));
                    TitleIdentityResult result=runtime.getMatcher().compare(identity(c.getString("a"),ka,c.optString("id_a","")),identity(c.getString("b"),kb,c.optString("id_b","")));
                    boolean positive=c.getString("expected").equals("SAME_ENTITY");
                    if(positive){positiveCases++;if(result.getDecision()==TitleIdentityDecision.SAME_ENTITY&&result.getVerified())verifiedPositives++;}
                    else {if(result.getDecision()==TitleIdentityDecision.SAME_ENTITY)falseSame++;if(TitleIdentityPolicies.INSTANCE.hideRecommendation(result)||TitleIdentityPolicies.INSTANCE.allowEnrichment(result))unsafeActions++;}
                }
                report.put("challenge_cases",cases.length());report.put("challenge_false_same",falseSame);report.put("challenge_unsafe_actions",unsafeActions);report.put("challenge_verified_positive",verifiedPositives);report.put("challenge_positive_cases",positiveCases);
                report.put("process_pss_kb",android.os.Debug.getPss());
                report.put("passed",report.getDouble("forest_max_probability_error")<1e-9&&report.getDouble("neural_max_probability_error")<.02&&report.getJSONObject("forest").getDouble("p95_ms")<=10&&featureError<1e-6&&coreError<1e-8&&falseSame==0&&unsafeActions==0);
            } catch(Throwable error) { try { report.put("passed",false);report.put("error",error.toString());report.put("error_location",error.getStackTrace().length>0?error.getStackTrace()[0].toString():""); } catch(Exception ignored) {} }
            android.util.Log.i("TitleMatchBench","BENCHMARK_RESULT "+report.toString());
            try (FileOutputStream file=new FileOutputStream(new File(getFilesDir(),"benchmark.json"))) { file.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); } catch(Exception error) {android.util.Log.e("TitleMatchBench","Result persistence failed",error);}
            String message=report.toString();runOnUiThread(()->text.setText(message));
        },"titlematch-benchmark").start();
    }
    byte[] readAsset(String name)throws Exception {try(InputStream input=getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}}
    static TitleIdentity identity(String title,TitleKind kind,String external) {
        java.util.Set<TitleExternalId> ids=external.isEmpty()?Collections.emptySet():Collections.singleton(new TitleExternalId(TitleNamespace.TMDB_MOVIE,external.substring(external.lastIndexOf(':')+1)));
        return new TitleIdentity(Collections.singletonList(title),kind,ids,null,null,kind==TitleKind.BOOK_WORK?TitleLevel.WORK:TitleLevel.CATALOG_ENTRY);
    }
    static void rewind(Object[] inputs){for(Object input:inputs)((ByteBuffer)input).rewind();}
    static JSONObject timing(double[] values)throws Exception {Arrays.sort(values);return new JSONObject().put("iterations",values.length).put("p50_ms",values[values.length/2]).put("p95_ms",values[(int)(values.length*.95)]).put("p99_ms",values[(int)(values.length*.99)]);}
    static float[] floats(JSONArray values)throws Exception {float[] result=new float[values.length()];for(int i=0;i<result.length;i++)result[i]=(float)values.getDouble(i);return result;}
    static String hex(byte[] bytes)throws Exception {byte[] value=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte b:value)result.append(String.format("%02x",b&255));return result.toString();}

    static final class Forest {
        final short[][] feature,left,right;final double[][] threshold,leaf;final int dimensions;
        Forest(byte[] bytes)throws Exception {
            if(bytes.length>1_500_000)throw new IllegalArgumentException("Model exceeds size budget");
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);byte[] magic=new byte[8];b.get(magic);
            if(!new String(magic,StandardCharsets.US_ASCII).equals("VTMF0001"))throw new IllegalArgumentException("Invalid model");
            dimensions=b.getInt();int trees=b.getInt();if(dimensions!=22||trees<1||trees>5000)throw new IllegalArgumentException("Dimensions");
            feature=new short[trees][];left=new short[trees][];right=new short[trees][];threshold=new double[trees][];leaf=new double[trees][];
            for(int t=0;t<trees;t++) {int n=b.getInt();if(n<1||n>32767)throw new IllegalArgumentException("Nodes");feature[t]=new short[n];left[t]=new short[n];right[t]=new short[n];threshold[t]=new double[n];leaf[t]=new double[n];
                for(int i=0;i<n;i++){feature[t][i]=b.getShort();b.getShort();threshold[t][i]=b.getDouble();left[t][i]=b.getShort();right[t][i]=b.getShort();leaf[t][i]=b.getDouble();if(feature[t][i]!=-1&&(feature[t][i]<0||feature[t][i]>=dimensions||left[t][i]<0||left[t][i]>=n||right[t][i]<0||right[t][i]>=n))throw new IllegalArgumentException("Edge");}
            }
            if(b.hasRemaining())throw new IllegalArgumentException("Trailing model bytes");
        }
        double predict(float[] values) {if(values.length!=dimensions)throw new IllegalArgumentException("Features");for(float v:values)if(!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite");double sum=0;
            for(int t=0;t<feature.length;t++){int i=0;for(int steps=0;steps<feature[t].length;steps++){int f=feature[t][i];if(f==-1){sum+=leaf[t][i];break;}i=values[f]<=threshold[t][i]?left[t][i]:right[t][i];if(steps==feature[t].length-1)throw new IllegalArgumentException("Cycle");}}
            return 1/(1+Math.exp(-Math.max(-700,Math.min(700,sum))));
        }
    }
}
