package io.ably.demo;

import java.util.ArrayList;

import com.google.gson.JsonObject;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import io.ably.demo.connection.Connection;
import io.ably.demo.connection.ConnectionCallback;
import io.ably.demo.connection.MessageHistoryRetrievedCallback;
import io.ably.demo.connection.PresenceHistoryRetrievedCallback;
import io.ably.lib.realtime.Channel;
import io.ably.lib.realtime.Presence;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.Message;
import io.ably.lib.types.PresenceMessage;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private final Handler isUserTypingHandler = new Handler();
    private final ArrayList<String> usersCurrentlyTyping = new ArrayList<>();
    private final ArrayList<String> presentUsers = new ArrayList<>();
    private final ConnectionCallback chatInitializedCallback = new ConnectionCallback() {
        @Override
        public void onConnectionCallback(Exception ex) {
            if (ex != null) {
                showError("Unable to connect to Ably service", ex);
                return;
            }

            addCurrentMembers();
            runOnUiThread(() -> {

                findViewById(R.id.progressBar).setVisibility(View.GONE);
                findViewById(R.id.chatLayout).setVisibility(View.VISIBLE);
                ((EditText) findViewById(R.id.textET)).removeTextChangedListener(isUserTypingTextWatcher);
                ((EditText) findViewById(R.id.textET)).addTextChangedListener(isUserTypingTextWatcher);
            });
        }
    };
    ChatScreenAdapter adapter;
    private final MessageHistoryRetrievedCallback getMessageHistoryCallback = new MessageHistoryRetrievedCallback() {
        @Override
        public void onMessageHistoryRetrieved(Iterable<Message> messages, Exception ex) {
            if (ex != null) {
                showError("Unable to retrieve message history", ex);
                return;
            }
            adapter.addItems(messages);
        }
    };
    private final PresenceHistoryRetrievedCallback getPresenceHistoryCallback = new PresenceHistoryRetrievedCallback() {
        @Override
        public void onPresenceHistoryRetrieved(Iterable<PresenceMessage> presenceMessages) {
            ArrayList<PresenceMessage> messagesExceptUpdates = new ArrayList<>();
            for (PresenceMessage message : presenceMessages) {
                if (message.action != PresenceMessage.Action.update) {
                    messagesExceptUpdates.add(message);
                }
            }

            adapter.addItems(messagesExceptUpdates);
        }
    };
    Channel.MessageListener messageListener = new Channel.MessageListener() {
        @Override
        public void onMessage(Message message) {
            adapter.addItem(message);
        }
    };
    Presence.PresenceListener presenceListener = new Presence.PresenceListener() {
        @Override
        public void onPresenceMessage(final PresenceMessage presenceMessage) {
            switch (presenceMessage.action) {
                case enter:
                    adapter.addItem(presenceMessage);
                    presentUsers.add(presenceMessage.clientId);
                    updatePresentUsersBadge();
                    break;
                case leave:
                    adapter.addItem(presenceMessage);
                    presentUsers.remove(presenceMessage.clientId);
                    updatePresentUsersBadge();
                    break;
                case update:
                    if (!presenceMessage.clientId.equals(Connection.getInstance().userName)) {
                        runOnUiThread(() -> {
                            boolean isUserTyping = ((JsonObject) presenceMessage.data).get("isTyping").getAsBoolean();
                            if (isUserTyping) {
                                usersCurrentlyTyping.add(presenceMessage.clientId);
                            } else {
                                usersCurrentlyTyping.remove(presenceMessage.clientId);
                            }

                            if (usersCurrentlyTyping.size() > 0) {
                                StringBuilder messageToShow = new StringBuilder();
                                switch (usersCurrentlyTyping.size()) {
                                    case 1:
                                        messageToShow.append(usersCurrentlyTyping.get(0) + " is typing");
                                        break;
                                    case 2:
                                        messageToShow.append(usersCurrentlyTyping.get(0) + " and ");
                                        messageToShow.append(usersCurrentlyTyping.get(1) + " are typing");
                                        break;
                                    default:
                                        if (usersCurrentlyTyping.size() > 4) {
                                            messageToShow.append(usersCurrentlyTyping.get(0) + ", ");
                                            messageToShow.append(usersCurrentlyTyping.get(1) + ", ");
                                            messageToShow.append(usersCurrentlyTyping.get(2) + " and other are typing");
                                        } else {
                                            int i;
                                            for (i = 0; i < usersCurrentlyTyping.size() - 1; ++i) {
                                                messageToShow.append(usersCurrentlyTyping.get(i) + ", ");
                                            }
                                            messageToShow.append(" and " + usersCurrentlyTyping.get(i) + " are typing");
                                        }
                                }

                                ((TextView) findViewById(R.id.isTyping)).setText(messageToShow.toString());
                                findViewById(R.id.isTypingContainer).setVisibility(View.VISIBLE);
                            } else {
                                findViewById(R.id.isTypingContainer).setVisibility(View.GONE);
                            }
                        });
                    }
                    break;
            }
        }
    };
    private boolean activityPaused = false;
    private boolean typingFlag = false;
    private final Runnable isUserTypingRunnable = () -> {
        Connection.getInstance().userHasEndedTyping();
        typingFlag = false;
    };
    private final TextWatcher isUserTypingTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            if (!typingFlag) {
                Connection.getInstance().userHasStartedTyping(ex -> {
                    if (ex != null) {
                        showError("Unable to send typing notification", ex);
                    }
                });
                typingFlag = true;
            }
            isUserTypingHandler.removeCallbacks(isUserTypingRunnable);
            isUserTypingHandler.postDelayed(isUserTypingRunnable, 5000);
        }
    };
    private String clientId;
    private final ConnectionCallback connectionCallback = ex -> {
        if (ex != null) {
            showError("Unable to connect", ex);
            return;
        }

        runOnUiThread(() -> showChatScreen());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.joinBtn).setOnClickListener(this);
        findViewById(R.id.mentionBtn).setOnClickListener(this);
        ((TextView) findViewById(R.id.textET)).setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                try {
                    CharSequence messageText = ((EditText) findViewById(R.id.textET)).getText();

                    if (TextUtils.isEmpty(messageText)) {
                        return false;
                    }

                    Connection.getInstance().sendMessage(messageText.toString(), ex -> {
                        if (ex != null) {
                            showError("Unable to send message", ex);
                            return;
                        }

                        runOnUiThread(() -> ((EditText) findViewById(R.id.textET)).setText(""));
                    });
                } catch (AblyException e) {
                    e.printStackTrace();
                }
            }
            return false;
        });
        ((TextView) this.findViewById(R.id.usernameET)).setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                MainActivity.this.onClick(MainActivity.this.findViewById(R.id.joinBtn));
            }

            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activityPaused) {
            Connection.getInstance().reconnectAbly();
            activityPaused = false;
        }
    }

    private void showChatScreen() {
        findViewById(R.id.loginLayout).setVisibility(View.GONE);

        adapter = new ChatScreenAdapter(this, this.clientId);
        ((ListView) findViewById(R.id.chatList)).setAdapter(adapter);
        try {
            Connection.getInstance().init(messageListener, presenceListener, ex -> {
                if (ex != null) {
                    showError("Unable to connect", ex);
                    return;
                }

                chatInitializedCallback.onConnectionCallback(ex);

                try {
                    Connection.getInstance().getMessagesHistory(MainActivity.this.getMessageHistoryCallback);
                    Connection.getInstance().getPresenceHistory(MainActivity.this.getPresenceHistoryCallback);
                } catch (AblyException e) {
                    chatInitializedCallback.onConnectionCallback(e);
                    e.printStackTrace();
                }
            });
        } catch (AblyException e) {
            e.printStackTrace();
        }
    }

    private void addCurrentMembers() {
        for (PresenceMessage presenceMessage : Connection.getInstance().getPresentUsers()) {
            if (!presenceMessage.clientId.equals(Connection.getInstance().userName)) {
                presentUsers.add(presenceMessage.clientId);
            }
        }
        updatePresentUsersBadge();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.joinBtn:
                View view = getCurrentFocus();
                if (view != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
                findViewById(R.id.loginLayout).setVisibility(View.GONE);
                findViewById(R.id.progressBar).setVisibility(View.VISIBLE);

                try {
                    this.clientId = ((TextView) findViewById(R.id.usernameET)).getText().toString();

                    Connection.getInstance().establishConnectionForID(this.clientId, connectionCallback);
                } catch (AblyException e) {
                    showError("Unable to connect", e);
                    Timber.e(e);
                }
                break;
            case R.id.mentionBtn:
                AlertDialog.Builder adBuilder = new AlertDialog.Builder(this);
                adBuilder.setSingleChoiceItems(new PresenceAdapter(this, presentUsers, this.clientId), -1,
                    (dialog, which) -> runOnUiThread(() -> {
                        String textToAppend = String.format("@%s ", presentUsers.get(which));
                        ((EditText) findViewById(R.id.textET)).append(textToAppend);
                        dialog.cancel();
                    }));
                adBuilder.setTitle("Handles");
                adBuilder.setIcon(R.drawable.user_list_title_icon);
                adBuilder.show();
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, final int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        runOnUiThread(() -> ((EditText) findViewById(R.id.textET)).setText(presentUsers.get(resultCode)));
    }

    private void updatePresentUsersBadge() {
        runOnUiThread(() -> ((TextView) findViewById(R.id.presenceBadge)).setText(String.valueOf(presentUsers.size())));
    }

    private void showError(final String title, final Exception ex) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, ex.getMessage(), Toast.LENGTH_LONG).show();
         /*   AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(MainActivity.this);
            dialogBuilder.setTitle(title);
            dialogBuilder.setMessage(ex.getMessage());
            dialogBuilder.setCancelable(true);
            dialogBuilder.show();*/
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityPaused = true;
        Connection.getInstance().disconnectAbly();
    }
}
