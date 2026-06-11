package com.example.twiliovoice;





import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.twilio.voice.CallException;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.Voice;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;

import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DeliveryVoiceApp";
    private static final int MIC_PERMISSION_REQUEST_CODE = 1;

    // ── Replace with your Twilio Serverless Function URL ──────────────────────
    private static final String TOKEN_SERVER_URL =
            "https://dh-app-backend-XXXX-dev.twil.io/token";
    // ──────────────────────────────────────────────────────────────────────────

    private EditText etIdentity;
    private EditText etDeliveryId;
    private Button   btnDial;
    private Button   btnDialIn;
    private View     layoutCallControls;
    private Button   btnMute;
    private Button   btnHangup;
    private TextView tvCallStatus;
    private TextView tvConferenceLabel;

    private com.twilio.voice.Call activeCall;
    private OkHttpClient httpClient = new OkHttpClient();
    private boolean isMuted = false;

    // ── Twilio Call Listener ───────────────────────────────────────────────────
    private com.twilio.voice.Call.Listener callListener = new com.twilio.voice.Call.Listener() {

        @Override
        public void onConnectFailure(@NonNull com.twilio.voice.Call call,
                                     @NonNull CallException e) {
            Log.e(TAG, "Connect failure: " + e.getMessage());
            setCallStatus("Connection failed");
            resetUI();
        }

        @Override
        public void onRinging(@NonNull com.twilio.voice.Call call) {
            activeCall = call;
            setCallStatus("Connecting…");
        }

        @Override
        public void onConnected(@NonNull com.twilio.voice.Call call) {
            activeCall = call;
            setCallStatus("In conference");
            showCallControls();
        }

        @Override
        public void onReconnecting(@NonNull com.twilio.voice.Call call,
                                   @NonNull CallException e) {
            setCallStatus("Reconnecting…");
        }

        @Override
        public void onReconnected(@NonNull com.twilio.voice.Call call) {
            setCallStatus("In conference");
        }

        @Override
        public void onDisconnected(@NonNull com.twilio.voice.Call call,
                                   CallException e) {
            activeCall = null;
            setCallStatus("Call ended");
            resetUI();
        }

        @Override
        public void onCallQualityWarningsChanged(
                @NonNull com.twilio.voice.Call call,
                @NonNull java.util.Set<com.twilio.voice.Call.CallQualityWarning> current,
                @NonNull java.util.Set<com.twilio.voice.Call.CallQualityWarning> previous) {}
    };
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIdentity          = findViewById(R.id.etIdentity);
        etDeliveryId        = findViewById(R.id.etDeliveryId);
        btnDial             = findViewById(R.id.btnDial);
        btnDialIn             = findViewById(R.id.btnDialIn);
        layoutCallControls  = findViewById(R.id.layoutCallControls);
        btnMute             = findViewById(R.id.btnMute);
        btnHangup           = findViewById(R.id.btnHangup);
        tvCallStatus        = findViewById(R.id.tvCallStatus);
        tvConferenceLabel   = findViewById(R.id.tvConferenceLabel);

        btnDial.setOnClickListener(v -> onDialClicked("caller"));
        btnDialIn.setOnClickListener(v -> onDialClicked("callee"));
        btnMute.setOnClickListener(v -> onMuteClicked());
        btnHangup.setOnClickListener(v -> onHangupClicked());

        requestMicrophonePermission();
    }

    // ── Button Handlers ────────────────────────────────────────────────────────

    private void onDialClicked(String mode) {
        String identity   = etIdentity.getText().toString().trim();
        String deliveryId = etDeliveryId.getText().toString().trim();

        if (identity.isEmpty()) {
            etIdentity.setError("Enter your identity");
            return;
        }
        if (deliveryId.isEmpty()) {
            etDeliveryId.setError("Enter the delivery ID");
            return;
        }

        btnDial.setEnabled(false);
        btnDial.setText("Connecting…");
        setCallStatus("Fetching token…");
        fetchTokenAndDial(identity, deliveryId, mode);
    }

    private void onMuteClicked() {
        if (activeCall != null) {
            isMuted = !isMuted;
            activeCall.mute(isMuted);
            btnMute.setText(isMuted ? "Unmute" : "Mute");
        }
    }

    private void onHangupClicked() {
        if (activeCall != null) {
            activeCall.disconnect();
        }
    }

    // ── Token fetch → dial ─────────────────────────────────────────────────────

    private void fetchTokenAndDial(String identity, String deliveryId,String mode) {
        RequestBody body = new FormBody.Builder()
                .add("identity",   identity)
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_SERVER_URL)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                Log.e(TAG, "Token fetch failed: " + e.getMessage());
                runOnUiThread(() -> {
                    setCallStatus("Failed to fetch token");
                    resetUI();
                });
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call,
                                   @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                                String errorReason = "";
                                try {
                                    String json = response.body().string();
                                    errorReason = new JSONObject(json).getString("error");
                                }catch(Exception ex1){
                                }


                        setCallStatus("Token server error: " + ((!errorReason.isEmpty())?errorReason:response.code()));
                        resetUI();
                    });
                    return;
                }
                try {
                    String json  = response.body().string();
                    String token = new JSONObject(json).getString("token");
                    runOnUiThread(() -> dialConference(token, deliveryId,mode));
                } catch (JSONException e) {
                    runOnUiThread(() -> {
                        setCallStatus("Invalid token response");
                        resetUI();
                    });
                }
            }
        });
    }

    private void dialConference(String accessToken, String deliveryId, String mode) {
        HashMap<String, String> params = new HashMap<>();
        params.put("deliveryId", deliveryId);
        params.put("mode",mode);

        Log.e(TAG, "Dialing Call: " + params.toString());

        ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                .params(params)
                .build();

        activeCall = Voice.connect(this, connectOptions, callListener);
        tvConferenceLabel.setText("Call Queue: " + deliveryId);
    }

    // ── UI Helpers ─────────────────────────────────────────────────────────────

    private void setCallStatus(String status) {
        runOnUiThread(() -> tvCallStatus.setText(status));
    }

    private void showCallControls() {
        runOnUiThread(() -> {
            btnDial.setVisibility(View.GONE);
            etIdentity.setEnabled(false);
            etDeliveryId.setEnabled(false);
            layoutCallControls.setVisibility(View.VISIBLE);
            isMuted = false;
            btnMute.setText("Mute");
        });
    }

    private void resetUI() {
        runOnUiThread(() -> {
            btnDial.setEnabled(true);
            btnDial.setText("Dial");
            btnDial.setVisibility(View.VISIBLE);
            etIdentity.setEnabled(true);
            etDeliveryId.setEnabled(true);
            layoutCallControls.setVisibility(View.GONE);
            tvConferenceLabel.setText("");
        });
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private void requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MIC_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_REQUEST_CODE
                && (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this,
                    "Microphone permission is required for calls",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        if (activeCall != null) {
            activeCall.disconnect();
            activeCall = null;
        }
        super.onDestroy();
    }
}