package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Completes a feature that previously had a fully-built, tested data
 * layer (add/update/delete_canned_reply, 8 pre-seeded starters) but no
 * actual screen to use it -- only the starter replies were usable, via
 * the clipboard-copy overlay. This is the missing management UI: list,
 * add, edit, delete.
 */
public class CannedRepliesActivity extends AppCompatActivity {

    private PyObject engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_canned_replies);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Canned Replies");
        }

        EditText newReplyInput = findViewById(R.id.newReplyInput);
        Button addReplyButton = findViewById(R.id.addReplyButton);
        LinearLayout repliesContainer = findViewById(R.id.repliesContainer);

        addReplyButton.setOnClickListener(v -> {
            String text = newReplyInput.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a reply first, then tap Add.", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                engine.callAttr("add_canned_reply", text);
                newReplyInput.setText("");
                Toast.makeText(this, "Added.", Toast.LENGTH_SHORT).show();
                refreshRepliesList(repliesContainer);
            } catch (RuntimeException e) { // covers PyException too
                Toast.makeText(this, "Could not add reply: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        refreshRepliesList(repliesContainer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout repliesContainer = findViewById(R.id.repliesContainer);
        refreshRepliesList(repliesContainer);
    }

    /**
     * Rebuilds the full list of reply rows from scratch -- simplest
     * correct way to keep the displayed list in sync after any
     * add/edit/delete, given this is a plain LinearLayout, not a
     * RecyclerView with its own diffing.
     */
    private void refreshRepliesList(LinearLayout container) {
        container.removeAllViews();
        try {
            JSONArray replies = new JSONArray(engine.callAttr("get_canned_replies_json").toString());
            if (replies.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("No replies yet -- add one above.");
                container.addView(empty);
                return;
            }
            for (int i = 0; i < replies.length(); i++) {
                JSONObject reply = replies.optJSONObject(i);
                if (reply == null) {
                    continue;
                }
                int replyId = reply.optInt("id", -1);
                String text = reply.optString("text", "");
                container.addView(buildReplyRow(replyId, text));
            }
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not load replies: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** One row: the reply text, plus Edit and Delete buttons -- matches the established programmatic-row-building pattern (e.g. the feedback dialog's category rows), since setItems only supports a single tap action per row and this genuinely needs two. */
    private LinearLayout buildReplyRow(int replyId, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);

        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button editButton = new Button(this);
        editButton.setText("Edit");
        editButton.setOnClickListener(v -> showEditDialog(replyId, text));

        Button deleteButton = new Button(this);
        deleteButton.setText("Delete");
        deleteButton.setOnClickListener(v -> {
            try {
                engine.callAttr("delete_canned_reply", replyId);
                Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show();
                refreshRepliesList((LinearLayout) findViewById(R.id.repliesContainer));
            } catch (RuntimeException e) { // covers PyException too
                Toast.makeText(this, "Could not delete: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        row.addView(textView);
        row.addView(editButton);
        row.addView(deleteButton);
        return row;
    }

    private void showEditDialog(int replyId, String currentText) {
        EditText input = new EditText(this);
        input.setText(currentText);
        new AlertDialog.Builder(this)
                .setTitle("Edit Reply")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newText = input.getText().toString().trim();
                    if (newText.isEmpty()) {
                        Toast.makeText(this, "Reply can't be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        engine.callAttr("update_canned_reply", replyId, newText);
                        Toast.makeText(this, "Updated.", Toast.LENGTH_SHORT).show();
                        refreshRepliesList((LinearLayout) findViewById(R.id.repliesContainer));
                    } catch (RuntimeException e) { // covers PyException too
                        Toast.makeText(this, "Could not update: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
