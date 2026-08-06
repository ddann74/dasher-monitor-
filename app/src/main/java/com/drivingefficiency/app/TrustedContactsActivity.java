package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TrustedContactsActivity extends AppCompatActivity {

    private PyObject engine;
    private static final int REQUEST_SAVE_CONTACTS_FILE = 3001;
    private static final int REQUEST_LOAD_CONTACTS_FILE = 3002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trusted_contacts);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Trusted Contacts");
        }

        EditText trustedContactInput = findViewById(R.id.trustedContactInput);
        Button addTrustedContactButton = findViewById(R.id.addTrustedContactButton);
        Button viewTrustedContactsButton = findViewById(R.id.viewTrustedContactsButton);
        Button saveContactsToFileButton = findViewById(R.id.saveContactsToFileButton);
        Button loadContactsFromFileButton = findViewById(R.id.loadContactsFromFileButton);

        addTrustedContactButton.setOnClickListener(v -> {
            String name = trustedContactInput.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Type a name first, then tap Add.", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                engine.callAttr("add_trusted_sender", name);
                trustedContactInput.setText("");
                Toast.makeText(this, "Added: \"" + name + "\" -- will match any sender "
                        + "whose name contains this.", Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) { // covers PyException too
                Toast.makeText(this, "Could not add contact: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        viewTrustedContactsButton.setOnClickListener(v -> showTrustedContacts());

        // Uses the SYSTEM file picker (ACTION_CREATE_DOCUMENT /
        // ACTION_OPEN_DOCUMENT) rather than the app's own internal
        // storage -- deliberately, since the whole point is surviving a
        // reinstall/upgrade. Internal app storage (cache/files dir, same
        // place CSV/report exports use) gets WIPED on uninstall; a file
        // saved via the system picker (e.g. to Downloads) lives outside
        // the app entirely and survives it.
        saveContactsToFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "dasher_monitor_trusted_contacts.txt");
            startActivityForResult(intent, REQUEST_SAVE_CONTACTS_FILE);
        });

        loadContactsFromFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            startActivityForResult(intent, REQUEST_LOAD_CONTACTS_FILE);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        android.net.Uri uri = data.getData();

        if (requestCode == REQUEST_SAVE_CONTACTS_FILE) {
            saveContactsToUri(uri);
        } else if (requestCode == REQUEST_LOAD_CONTACTS_FILE) {
            loadContactsFromUri(uri);
        }
    }

    /** Writes every current trusted contact, one name per line, to the file the user just picked/named. */
    private void saveContactsToUri(android.net.Uri uri) {
        try {
            JSONArray names = new JSONArray(engine.callAttr("get_trusted_senders_json").toString());
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < names.length(); i++) {
                body.append(names.optString(i, "")).append("\n");
            }
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            rememberContactsFileUri(uri);
            Toast.makeText(this, "Saved " + names.length() + " trusted contact(s) to file.",
                    Toast.LENGTH_LONG).show();
        } catch (RuntimeException | JSONException | java.io.IOException e) {
            Toast.makeText(this, "Could not save file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Remembers this file's URI (surviving app restarts, via a
     * persistable permission grant) so a future launch can automatically
     * reload it if the trusted-contacts list is ever found completely
     * empty -- e.g. after a reinstall or a data reset. Deliberately only
     * ever triggers when the list is empty, never overwriting a list
     * that already has contacts in it (see the auto-recovery check in
     * MainActivity).
     */
    private void rememberContactsFileUri(android.net.Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers don't support persistable permissions -- the
            // save/load itself already succeeded, this just means
            // auto-recovery won't be able to use this particular file.
        }
        getSharedPreferences("dasher_monitor_prefs", MODE_PRIVATE).edit()
                .putString("last_trusted_contacts_file_uri", uri.toString())
                .apply();
    }

    /** Reads the picked file, one name per line, adding each as a trusted contact -- exactly what re-entering by hand after a reinstall would have done, just from a file instead. */
    private void loadContactsFromUri(android.net.Uri uri) {
        try {
            int addedCount = 0;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    Toast.makeText(this, "Could not open that file.", Toast.LENGTH_LONG).show();
                    return;
                }
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.trim();
                    if (!name.isEmpty()) {
                        engine.callAttr("add_trusted_sender", name);
                        addedCount++;
                    }
                }
            }
            rememberContactsFileUri(uri);
            Toast.makeText(this, "Loaded " + addedCount + " trusted contact(s) from file.",
                    Toast.LENGTH_LONG).show();
        } catch (RuntimeException | java.io.IOException e) {
            Toast.makeText(this, "Could not load file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
         * Shows current trusted contacts; tapping a name removes it. Enter the
         * name exactly as it appears in the notification title -- typically the
         * contact's saved name for SMS/Messenger, or the raw phone number if
         * they aren't saved in your phone contacts.
         */
        private void showTrustedContacts() {
            try {
                JSONArray namesJson = new JSONArray(engine.callAttr("get_trusted_senders_json").toString());
                if (namesJson.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Trusted Contacts")
                            .setMessage("No trusted contacts added yet. Add a name above -- "
                                    + "only messages from people on this list get read aloud.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                String[] names = new String[namesJson.length()];
                for (int i = 0; i < namesJson.length(); i++) {
                    names[i] = namesJson.optString(i, "");
                }
                new AlertDialog.Builder(this)
                        .setTitle("Trusted Contacts (tap to remove)")
                        .setItems(names, (dialog, which) -> {
                            engine.callAttr("remove_trusted_sender", names[which]);
                            Toast.makeText(this, "Removed: " + names[which], Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Close", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load trusted contacts.", Toast.LENGTH_SHORT).show();
            }
        }
}
