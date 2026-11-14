package com.example.aistudyplanner;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.net.*;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import androidx.browser.customtabs.*;
import androidx.browser.trusted.TrustedWebActivityIntentBuilder;

/** Existing responsive web UI runs in the browser. No WebView, JS bridge or embedded credentials. */
public final class MainActivity extends Activity {
    private TextView status;
    private Button launch;
    private CustomTabsServiceConnection connection;
    private String origin;
    private boolean bound;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final ConnectionAttempt attempts = new ConnectionAttempt();
    private Runnable connectionTimeout;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);origin=OriginPolicy.approvedOrigin(BuildConfig.WEB_ORIGIN);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(48,100,48,48);layout.setBackgroundColor(Color.rgb(244,246,251));
        TextView title=new TextView(this);title.setText("AI 공부 플래너");title.setTextSize(28);layout.addView(title);
        status=new TextView(this);status.setTextSize(17);status.setPadding(0,32,0,32);layout.addView(status);
        launch=new Button(this);launch.setText("학습 플래너 열기 / 다시 연결");launch.setOnClickListener(v->open());layout.addView(launch);
        Button browser=new Button(this);browser.setText("기본 브라우저에서 열기");browser.setOnClickListener(v->openBrowser());layout.addView(browser);
        setContentView(layout);
        if(origin==null){status.setText("아직 고정 HTTPS 서버 주소가 설정되지 않았습니다. 개발자가 승인된 도메인을 연결한 APK를 제공하면 로그인 후 사용할 수 있습니다. 이 APK는 서버에 연결하지 않습니다.");launch.setEnabled(false);browser.setEnabled(false);}
        else status.setText("로그인은 휴대폰 브라우저의 안전한 세션을 사용합니다. 서버 연결에 실패하면 이 화면으로 돌아와 다시 연결하세요.");
    }
    private void open(){
        if(origin==null)return;
        releaseConnection();
        final long attempt = attempts.begin();
        ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(manager.getActiveNetwork());
        if(capabilities==null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){attempts.finish(attempt);status.setText("네트워크가 연결되지 않았습니다. Wi-Fi 또는 모바일 데이터를 켠 뒤 다시 연결하세요.");return;}
        launch.setEnabled(false);status.setText("HTTPS 서버 연결을 확인 중입니다…");
        new Thread(()->{
            javax.net.ssl.HttpsURLConnection request=null;
            boolean reachable=false;
            try{
                request=(javax.net.ssl.HttpsURLConnection)new java.net.URL(origin+"/login.html").openConnection();
                request.setRequestMethod("HEAD");request.setConnectTimeout(5000);request.setReadTimeout(5000);
                request.setInstanceFollowRedirects(false);request.setUseCaches(false);
                int code=request.getResponseCode();reachable=ConnectionAttempt.reachableStatus(code);
            }catch(java.io.IOException ignored){ /* Default TLS validation is never bypassed. */ }
            finally{if(request!=null)request.disconnect();}
            final boolean ready=reachable;
            runOnUiThread(()->{if(isFinishing()||isDestroyed()||!attempts.isCurrent(attempt))return;if(ready)connectBrowser(attempt);else{attempts.finish(attempt);launch.setEnabled(true);status.setText("HTTPS 서버에 연결하지 못했습니다. 서버 실행·인증서·네트워크를 확인한 뒤 다시 연결하세요.");}});
        },"planner-readonly-reachability").start();
    }
    private void connectBrowser(long attempt){
        status.setText("안전한 브라우저에 연결 중입니다…");
        if(bound){unbindService(connection);bound=false;}
        String provider=CustomTabsClient.getPackageName(this,null);
        if(provider==null){launch.setEnabled(true);openBrowser();return;}
        connection=new CustomTabsServiceConnection(){
            @Override public void onCustomTabsServiceConnected(ComponentName name,CustomTabsClient client){
                if(isFinishing()||isDestroyed()||!attempts.finish(attempt))return;
                if(connectionTimeout!=null)handler.removeCallbacks(connectionTimeout);
                launch.setEnabled(true);client.warmup(0);CustomTabsSession session=client.newSession(new CustomTabsCallback());
                if(session==null){openBrowser();return;}
                try {new TrustedWebActivityIntentBuilder(Uri.parse(origin+"/dashboard.html")).build(session).launchTrustedWebActivity(MainActivity.this);
                    status.setText("브라우저를 열었습니다. 주소창이 보이면 도메인·앱 서명 연결이 아직 검증되지 않은 상태입니다. 서버 오류는 브라우저 안내를 확인하고 돌아와 다시 연결하세요.");
                } catch(RuntimeException error){status.setText("앱 연결을 시작하지 못했습니다. 기본 브라우저에서 열거나 다시 시도하세요.");}
            }
            @Override public void onServiceDisconnected(ComponentName name){if(!attempts.finish(attempt))return;if(connectionTimeout!=null)handler.removeCallbacks(connectionTimeout);launch.setEnabled(true);status.setText("브라우저 연결이 종료되었습니다. 다시 연결할 수 있습니다.");}
        };
        bound=CustomTabsClient.bindCustomTabsService(this,provider,connection);
        if(!bound){launch.setEnabled(true);openBrowser();}
        else {
            connectionTimeout=()->{if(!isFinishing()&&!isDestroyed()&&attempts.finish(attempt)){releaseConnection();launch.setEnabled(true);status.setText("브라우저 연결이 지연됩니다. 다시 연결하거나 기본 브라우저를 사용하세요.");}};
            handler.postDelayed(connectionTimeout,8000);
        }
    }
    private void releaseConnection(){if(connectionTimeout!=null){handler.removeCallbacks(connectionTimeout);connectionTimeout=null;}if(bound){unbindService(connection);bound=false;}}
    private void openBrowser(){if(origin==null)return;attempts.cancel();releaseConnection();launch.setEnabled(true);try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(origin+"/dashboard.html")));}catch(ActivityNotFoundException e){status.setText("HTTPS를 지원하는 브라우저를 설치한 뒤 다시 시도하세요.");}}
    @Override protected void onDestroy(){attempts.cancel();releaseConnection();super.onDestroy();}
}
