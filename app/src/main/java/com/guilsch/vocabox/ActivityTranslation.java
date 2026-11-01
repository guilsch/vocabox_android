package com.guilsch.vocabox;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import life.sabujak.roundedbutton.RoundedButton;

public class ActivityTranslation extends AppCompatActivity {

    // ----- Vue 1 : recherche -----
    private RoundedButton OKButton;
    private EditText item1Text;          // barre de recherche : le mot
    private TextView item2Text;          // réponse de l'API : définitions + étymologie
    private ImageView searchButton;
    private ProgressBar searchProgress;
    private ConstraintLayout backLayout;

    // ----- Vue 2 : vérification de la carte -----
    private RoundedButton saveCardButton;
    private EditText item1TextCheckCardLayout;   // le mot
    private EditText item2TextCheckCardLayout;   // définitions + étymologie (modifiable avant d'enregistrer)
    private EditText packText;
    private ConstraintLayout backLayoutCheck;

    private Card newCard;
    private Integer currentLayoutNum;

    // ----- Recherche -----
    private final DictionaryRepository repository = new DictionaryRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int searchId = 0;            // pour ignorer la réponse d'une ancienne recherche
    private String searchedWord = "";    // dernier mot recherché
    private String resultText = "";      // réponse de l'API pour ce mot ("" tant qu'il n'y en a pas)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initMainTranslationLayout();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // =====================================================================
    // Vue 1 : barre de recherche + réponse
    // =====================================================================

    private void initMainTranslationLayout() {
        setContentView(R.layout.activity_translation);

        currentLayoutNum = 1;

        backLayout = findViewById(R.id.back_layout);
        backLayout.setOnClickListener(v -> onBackPressed());

        OKButton = findViewById(R.id.ok_button);
        item1Text = findViewById(R.id.item1_text);
        item2Text = findViewById(R.id.item2_text);
        searchButton = findViewById(R.id.search_button);
        searchProgress = findViewById(R.id.search_progress);

        // Restaure l'état (utile quand on revient de la vue 2 avec « Back »)
        item1Text.setText(searchedWord);
        item2Text.setText(resultText);

        // Lancer la recherche : loupe ou touche « Rechercher » du clavier
        searchButton.setOnClickListener(v -> searchWord());
        item1Text.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchWord();
                return true;
            }
            return false;
        });

        // OK : passe à la vérification de la carte (mot + réponse de l'API)
        OKButton.setOnClickListener(view -> {
            String typedWord = item1Text.getText().toString().trim();

            if (typedWord.isEmpty()) {
                Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_empty_search));
            } else if (resultText.isEmpty() || !typedWord.equalsIgnoreCase(searchedWord)) {
                // Pas de réponse pour ce mot : il faut d'abord lancer la recherche
                Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_search_first));
            } else {
                setCheckCardLayout();
            }
        });
    }

    private void searchWord() {
        final String word = item1Text.getText().toString().trim();

        if (word.isEmpty()) {
            Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_empty_search));
            return;
        }

        hideKeyboard();

        searchedWord = word;
        resultText = "";
        item2Text.setText("");
        searchProgress.setVisibility(View.VISIBLE);

        final int currentSearch = ++searchId;

        // Réseau + parsing sur un thread secondaire
        executor.execute(() -> {
            try {
                WordEntry entry = repository.lookup(word);
                final String text = (entry == null) ? null : formatEntry(entry);
                mainHandler.post(() -> onSearchResult(currentSearch, word, text, false));
            } catch (IOException e) {
                mainHandler.post(() -> onSearchResult(currentSearch, word, null, true));
            }
        });
    }

    private void onSearchResult(int id, String word, String text, boolean networkError) {
        if (id != searchId || isFinishing() || isDestroyed()) return;

        if (text != null) {
            resultText = text;
        }

        // Si l'utilisateur est déjà passé à la vue 2, rien à afficher ici
        if (currentLayoutNum != 1) return;

        searchProgress.setVisibility(View.GONE);

        if (networkError) {
            item2Text.setText(R.string.msg_search_error);
        } else if (text == null) {
            item2Text.setText(getString(R.string.msg_no_definition, word));
        } else {
            item2Text.setText(text);
        }
    }

    /** Transforme l'entrée du dictionnaire en texte brut (réutilisé tel quel pour la carte). */
    private String formatEntry(WordEntry entry) {
        StringBuilder sb = new StringBuilder();

        for (WordEntry.Meaning meaning : entry.meanings) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(meaning.category);
            for (int i = 0; i < meaning.definitions.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(meaning.definitions.get(i));
            }
        }

        if (entry.etymology != null) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(getString(R.string.label_etymology)).append("\n").append(entry.etymology);
        }

        return sb.toString();
    }

    // =====================================================================
    // Vue 2 : vérification de la carte avant enregistrement
    // =====================================================================

    private void setCheckCardLayout() {
        setContentView(R.layout.activity_translation_check);
        currentLayoutNum = 2;

        item1TextCheckCardLayout = findViewById(R.id.item1_text);
        item2TextCheckCardLayout = findViewById(R.id.item2_text);
        packText = findViewById(R.id.pack_text);

        // « Back » : retour à la recherche, avec le mot et la réponse conservés
        backLayoutCheck = findViewById(R.id.back_layout);
        backLayoutCheck.setOnClickListener(v -> initMainTranslationLayout());

        saveCardButton = findViewById(R.id.save_card_button);
        saveCardButton.setOnClickListener(view -> saveCardButtonClick());

        item1TextCheckCardLayout.setText(searchedWord);
        item2TextCheckCardLayout.setText(resultText);
    }

    public void saveCardButtonClick() {
        String item1 = item1TextCheckCardLayout.getText().toString();
        String item2 = item2TextCheckCardLayout.getText().toString();

        if (item1.isEmpty() || item2.isEmpty()) {
            new AlertDialog.Builder(ActivityTranslation.this)
                    .setTitle("Unvalid card")
                    .setMessage("Both the word and its definition have to be set")
                    .setNegativeButton(android.R.string.ok, null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            return;
        }

        newCard = new Card(item1, item2, Param.INACTIVE, packText.getText().toString(), Utils.giveCurrentDate(), Utils.giveCurrentDate(), 0, 0, 0, Utils.getNewUUID(), -1);
        Utils.manageCardCreation(newCard);

        // Nouvelle recherche : on repart de zéro
        searchedWord = "";
        resultText = "";
        initMainTranslationLayout();
        Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_new_card_saved));
    }

    // =====================================================================
    // Divers
    // =====================================================================

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focused = getCurrentFocus();
        if (imm != null && focused != null) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {

        if (currentLayoutNum == 1) {
            Intent menuActivityIntent = new Intent(getApplicationContext(), ActivityMenu.class);
            menuActivityIntent.putExtra("FRAG_INDEX", 2);
            startActivity(menuActivityIntent);
            finish();
        }

        else if (currentLayoutNum == 2) {
            initMainTranslationLayout();
        }
    }
}
