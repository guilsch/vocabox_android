package com.guilsch.vocabox;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import life.sabujak.roundedbutton.RoundedButton;

/**
 * This class is the activity corresponding to the learning activity.
 *
 * Flow : the user selects the cards (words and their definitions) to learn, then goes directly
 * to the evaluation (question side, answer side, quality from 1 to 4).
 *
 * @author Guilhem Schena
 */
public class ActivityLearn extends AppCompatActivity implements View.OnClickListener {

    private RoundedButton mStartLearningButton;

    private ImageView speaker;

    private RoundedButton mSeeAnswerButtonStep3;
    private RoundedButton mAnswerButton1Step3;
    private RoundedButton mAnswerButton2Step3;
    private RoundedButton mAnswerButton3Step3;
    private RoundedButton mAnswerButton4Step3;
    private TextView mTextViewQuestionStep3;
    private TextView mTextViewAnswerStep3Item1;
    private TextView mTextViewAnswerStep3Item2;

    private RoundedButton mBackToMenuRevisionButton;

    ListView cardsSelectionList;

    private Card currentCard;
    // Cards ticked on the selection screen (also used by the selection adapter)
    private static Queue<Card> selectedCardsQueue;
    // Cards to evaluate. A card rated 1 is put back at the end of this queue
    private Queue<Card> learningCardsQueue;
    private Queue<Card> processedCardsQueue;
    private List<Card> toLearnCardsList;

    // Progress Bar
    private ProgressBar CardsRemainingPB;
    private TextView cardsLeftText;
    private int cardsNBInit;

    // Back Arrow
    private ConstraintLayout backLayout;

    // Thread
    private ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    // Text to speech
    TextToSpeech textToSpeech;

    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize variables
        selectedCardsQueue = new LinkedList<>();
        learningCardsQueue = new LinkedList<>();
        processedCardsQueue = new LinkedList<>();

        toLearnCardsList = Param.GLOBAL_DECK.getCardsToLearnList();

        // Initialize text to speech
        textToSpeech = Pronunciator.initPronunciator(this, Param.TARGET_LANGUAGE_ISO);

        setLayoutCardsSelection();
    }

    /**
     * Manage the clicks on the seeAnswerButton and the 4 answerButtons, as well as the
     * backToMenuButton
     *
     * @param v view pressed by the user
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    public void onClick(View v) {

        if (v == mSeeAnswerButtonStep3) {
            showAnswerSideStep3();

        } else if (v == mAnswerButton1Step3){
            scrollCardsStep3(1);

        } else if (v == mAnswerButton2Step3) {
            scrollCardsStep3(2);

        } else if (v == mAnswerButton3Step3) {
            scrollCardsStep3(3);

        } else if (v == mAnswerButton4Step3) {
            scrollCardsStep3(4);

        } else if (v == mBackToMenuRevisionButton) {
            onBackPressed();
        }

        else {
            throw new IllegalStateException("Unknown clicked view : " + v);
        }
    }

    /**
     * Occurs after the user gave an answer. Manage the score given by the user for the current
     * card. Mark the card active and calculate the new training date thanks to SuperMemo algorithm.
     * Add the current card to the processed cards queue only if the card is not repeated (ie score
     * not 1). Else remains in the learningCardsQueue queue.
     *
     * When currentCard is null (start of the training), there is no answer to record : the method
     * only shows the first card.
     *
     * @param quality 1 to 4, quality of the answer given by the user
     */
    private void scrollCardsStep3(int quality) {

        if (currentCard != null) {
            // Calculates next date
            MemoAlgo.SuperMemo2(currentCard, quality);

            // Manage quality
            if (quality != 1) {
                // Change card values
                currentCard.setState(Param.ACTIVE);
                Utils.updateInDatabaseOnSeparateThreadMultiShot(singleThreadExecutor, currentCard);

                // Add to processed queue
                processedCardsQueue.add(currentCard);

            } else {
                learningCardsQueue.add(currentCard);
            }
        }

        // Next card
        currentCard = learningCardsQueue.poll();

        if (currentCard != null) {
            // Show new card
            showQuestionSideStep3();
        }
        else {
            Utils.threadShutdown(singleThreadExecutor);
            showEndOfLearning();
        }
    }

    /**
     * Set the layout to select the cards to learn
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void setLayoutCardsSelection() {
        setContentView(R.layout.activity_learn_cards_selection);

        backLayout = findViewById(R.id.back_layout);
        backLayout.setOnClickListener(v -> onBackPressed());

        // Cards selection
        cardsSelectionList = findViewById(R.id.cardsSelectionListView);
        AdapterDeckCardsSelection adapter = new AdapterDeckCardsSelection(getApplicationContext(), toLearnCardsList);
        cardsSelectionList.setAdapter(adapter);

        // Start learning button
        mStartLearningButton = findViewById(R.id.cards_selection_start_button);
        mStartLearningButton.setOnClickListener(v -> onStartLearningButton());

        cardsSelectionList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Card selectedCard = (Card) parent.getItemAtPosition(position);
                selectedCard.setIsSelected(!selectedCard.getSelected());

                if (selectedCard.getSelected()) {
                    ActivityLearn.addToLearningQueue(selectedCard);
                } else {
                    ActivityLearn.removeFromLearningQueue(selectedCard);
                }
                adapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Occurs when the user starts the training from the cards selection. The old steps 1 and 2
     * have been removed : the selected cards go directly to the evaluation.
     */
    private void onStartLearningButton() {
        resetSelectionFlags();

        learningCardsQueue.clear();
        learningCardsQueue.addAll(selectedCardsQueue);
        processedCardsQueue.clear();
        cardsNBInit = learningCardsQueue.size();

        // No card shown yet : scrollCardsStep3 only polls and shows the first card
        currentCard = null;
        scrollCardsStep3(0);
    }

    /**
     * Unmark the selected cards (the selection screen starts clean next time). The cards stay in
     * the selection queue.
     */
    private void resetSelectionFlags() {
        for (Card card : selectedCardsQueue) {
            card.setIsSelected(false);
        }
    }

    /**
     * Set the end of learning layout when the user is done with the training
     */
    private void showEndOfLearning() {
        setContentView(R.layout.activity_learn_end_of_learning);

        // Save changes in deck
        Param.GLOBAL_DECK.updateDeckDataVariables();

        mBackToMenuRevisionButton = findViewById(R.id.skip_btn);
        mBackToMenuRevisionButton.setOnClickListener(this);
    }

    /**
     * Set the layout for the question side for the current card
     */
    private void showQuestionSideStep3() {
        setContentView(R.layout.card_question_side);

        backLayout = findViewById(R.id.back_layout);
        backLayout.setOnClickListener(v -> onBackToSelectionPressed());

        cardsLeftText = findViewById(R.id.cards_left);

        mTextViewQuestionStep3 = findViewById(R.id.question_side_item1);
        mSeeAnswerButtonStep3 = findViewById(R.id.question_side_button);

        mSeeAnswerButtonStep3.setOnClickListener(this);
        mTextViewQuestionStep3.setText(this.currentCard.getItem1());

        updateCardsRemainingProgressBar();
        cardsLeftText.setText(String.valueOf(cardsNBInit - processedCardsQueue.size()));
    }

    /**
     * Set the layout for the answer side for the current card
     */
    private void showAnswerSideStep3() {
        setContentView(R.layout.card_answer_side);

        backLayout = findViewById(R.id.back_layout);
        backLayout.setOnClickListener(v -> onBackToSelectionPressed());

        cardsLeftText = findViewById(R.id.cards_left);

        mTextViewAnswerStep3Item1 = findViewById(R.id.answer_side_item1);
        mTextViewAnswerStep3Item2 = findViewById(R.id.answer_side_item2);

        mAnswerButton1Step3 = findViewById(R.id.answer_side_button1);
        mAnswerButton2Step3 = findViewById(R.id.answer_side_button2);
        mAnswerButton3Step3 = findViewById(R.id.answer_side_button3);
        mAnswerButton4Step3 = findViewById(R.id.answer_side_button4);
        speaker = findViewById(R.id.speaker_ic);

        mAnswerButton1Step3.setOnClickListener(this);
        mAnswerButton2Step3.setOnClickListener(this);
        mAnswerButton3Step3.setOnClickListener(this);
        mAnswerButton4Step3.setOnClickListener(this);

        mTextViewAnswerStep3Item1.setText(this.currentCard.getItem1());
        mTextViewAnswerStep3Item2.setText(this.currentCard.getItem2());

        // Pronunciation
        textToSpeech.speak(currentCard.getItem2(), TextToSpeech.QUEUE_FLUSH, null, null);
        speaker.setOnClickListener(v -> textToSpeech.speak(currentCard.getItem2(),
                TextToSpeech.QUEUE_FLUSH, null, null));

        updateCardsRemainingProgressBar();
        cardsLeftText.setText(String.valueOf(cardsNBInit - processedCardsQueue.size()));
    }

    /**
     * Update cards remaining progress bar
     */
    private void updateCardsRemainingProgressBar() {
        CardsRemainingPB = findViewById(R.id.remaining_cards_progress_bar);
        CardsRemainingPB.setMax(cardsNBInit);
        CardsRemainingPB.setProgress(processedCardsQueue.size());
    }

    /**
     * When the card is selected, addLearningQueue(card) is called in the CardsSelectionListAdapter.
     * This method adds the card selected in the selection queue.
     *
     * @param card Card selected to be learned
     */
    public static void addToLearningQueue(Card card) {
        System.out.println(card.getItem1() + " is added");
        selectedCardsQueue.add(card);
    }

    /**
     * When the card is unselected, removeFromLearningQueue(card) is called in the
     * CardsSelectionListAdapter. This method removes the card selected from the selection queue.
     *
     * @param card Card selected to be removed
     */
    public static void removeFromLearningQueue(Card card) {
        System.out.println(card.getItem1() + " is removed");
        selectedCardsQueue.remove(card);
    }

    /**
     * learningQueueContains(card) is called in the CardsSelectionListAdapter to check if a
     * particular card is currently selected.
     *
     * @param card Card selected to check if it is in the selection queue.
     */
    public static Boolean learningQueueContains(Card card) {
        Boolean contains = selectedCardsQueue.contains(card);
        return contains;
    }

    private void onBackToSelectionPressed() {
        DialogQuitLearning.showCustomDialog(ActivityLearn.this);
    }

    @Override
    public void onBackPressed() {
        resetSelectionFlags();

        Utils.threadShutdown(singleThreadExecutor);

        Intent menuActivity = new Intent(getApplicationContext(), ActivityMenu.class);
        startActivity(menuActivity);
        finish();
    }
}