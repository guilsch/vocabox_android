package com.guilsch.vocabox;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private ListView suggestionsList;                 // suggestions affichées sous la barre
    private ArrayAdapter<String> suggestionsAdapter;
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

    // ----- Suggestions pendant la frappe -----
    private static final int SUGGEST_MIN_CHARS = 2;      // nombre de lettres avant de suggérer
    private static final long SUGGEST_DELAY_MS = 250;    // attente après la dernière frappe
    private static final int SUGGEST_MAX = 8;            // nombre de suggestions affichées

    private final ExecutorService suggestExecutor = Executors.newSingleThreadExecutor();
    private Runnable suggestRunnable;                    // requête en attente (anti-rebond)
    private int suggestId = 0;                           // pour ignorer les réponses périmées
    private boolean suppressSuggestions = false;         // true quand on modifie le texte par code

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
        suggestExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
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

        suggestionsList = findViewById(R.id.suggestions_list);
        suggestionsAdapter = new ArrayAdapter<>(this, R.layout.item_suggestion, new ArrayList<String>());
        suggestionsList.setAdapter(suggestionsAdapter);

        // Restaure l'état (utile quand on revient de la vue 2 avec « Back »)
        item1Text.setText(searchedWord);
        item2Text.setText(resultText);

        // Suggestions de mots pendant la frappe (le watcher est ajouté après la restauration du texte)
        item1Text.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressSuggestions) return;
                onSearchTextChanged(s.toString().trim());
            }
        });

        // Clic sur une suggestion : remplit la barre et lance directement la recherche
        suggestionsList.setOnItemClickListener((parent, view, position, id) -> {
            String word = suggestionsAdapter.getItem(position);
            if (word == null) return;
            suppressSuggestions = true;
            item1Text.setText(word);
            item1Text.setSelection(word.length());
            suppressSuggestions = false;
            searchWord();
        });

        hideSuggestions();

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
//        OKButton.setOnClickListener(view -> saveCardButtonClick());
        OKButton.setOnClickListener(view -> {
            String typedWord = item1Text.getText().toString().trim();

            if (typedWord.isEmpty()) {
                Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_empty_search));
            } else if (resultText.isEmpty() || !typedWord.equalsIgnoreCase(searchedWord)) {
                // Pas de réponse pour ce mot : il faut d'abord lancer la recherche
                Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_search_first));
            } else {
                saveCardButtonClick();
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

        hideSuggestions();
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

    // ---------- Suggestions pendant la frappe ----------

    private void onSearchTextChanged(String typed) {
        if (suggestRunnable != null) mainHandler.removeCallbacks(suggestRunnable);

        if (typed.length() < SUGGEST_MIN_CHARS) {
            hideSuggestions();
            return;
        }

        // On attend que l'utilisateur marque une pause avant d'appeler le réseau
        suggestRunnable = () -> fetchSuggestions(typed);
        mainHandler.postDelayed(suggestRunnable, SUGGEST_DELAY_MS);
    }

    private void fetchSuggestions(String prefix) {
        final int currentSuggest = ++suggestId;

        suggestExecutor.execute(() -> {
            List<String> words;
            try {
                words = repository.suggest(prefix, SUGGEST_MAX);
            } catch (IOException e) {
                words = Collections.emptyList();   // pas de suggestions, sans message d'erreur
            }
            final List<String> result = words;
            mainHandler.post(() -> showSuggestions(currentSuggest, result));
        });
    }

    private void showSuggestions(int id, List<String> words) {
        if (id != suggestId || currentLayoutNum != 1 || isFinishing() || isDestroyed()) return;

        if (words.isEmpty()) {
            suggestionsList.setVisibility(View.GONE);
            return;
        }

        suggestionsAdapter.clear();
        suggestionsAdapter.addAll(words);
        suggestionsList.setVisibility(View.VISIBLE);
    }

    private void hideSuggestions() {
        suggestId++;   // invalide les requêtes en cours
        if (suggestRunnable != null) mainHandler.removeCallbacks(suggestRunnable);
        if (suggestionsList != null) suggestionsList.setVisibility(View.GONE);
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

//    public void saveCardButtonClick() {
//        String item1 = item1TextCheckCardLayout.getText().toString();
//        String item2 = item2TextCheckCardLayout.getText().toString();
//
//        if (item1.isEmpty() || item2.isEmpty()) {
//            new AlertDialog.Builder(ActivityTranslation.this)
//                    .setTitle("Unvalid card")
//                    .setMessage("Both the word and its definition have to be set")
//                    .setNegativeButton(android.R.string.ok, null)
//                    .setIcon(android.R.drawable.ic_dialog_alert)
//                    .show();
//            return;
//        }
//
//        newCard = new Card(item1, item2, Param.INACTIVE, packText.getText().toString(), Utils.giveCurrentDate(), Utils.giveCurrentDate(), 0, 0, 0, Utils.getNewUUID(), -1);
//        Utils.manageCardCreation(newCard);
//
//        // Nouvelle recherche : on repart de zéro
//        searchedWord = "";
//        resultText = "";
//        initMainTranslationLayout();
//        Utils.showToast(ActivityTranslation.this, getString(R.string.toast_msg_new_card_saved));
//    }

    public void saveCardButtonClick() {
        String item1 = item1Text.getText().toString();
        String item2 = item2Text.getText().toString();

        if (item1.isEmpty() || item2.isEmpty()) {
            new AlertDialog.Builder(ActivityTranslation.this)
                    .setTitle("Unvalid card")
                    .setMessage("Both the word and its definition have to be set")
                    .setNegativeButton(android.R.string.ok, null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            return;
        }

        newCard = new Card(item1, item2, Param.INACTIVE, "", Utils.giveCurrentDate(), Utils.giveCurrentDate(), 0, 0, 0, Utils.getNewUUID(), -1);
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
