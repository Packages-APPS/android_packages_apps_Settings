/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.homepage.contextualcards;

import static com.android.settings.intelligence.ContextualCardProto.ContextualCard.Category.STICKY_VALUE;
import static com.android.settings.slices.CustomSliceRegistry.BLUETOOTH_DEVICES_SLICE_URI;
import static com.android.settings.slices.CustomSliceRegistry.CONTEXTUAL_NOTIFICATION_CHANNEL_SLICE_URI;
import static com.android.settings.slices.CustomSliceRegistry.CONTEXTUAL_WIFI_SLICE_URI;

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.settings.R;
import com.android.settings.homepage.contextualcards.logging.ContextualCardLogUtils;
import com.android.settings.overlay.FeatureFactory;
import com.android.settingslib.core.instrumentation.MetricsFeatureProvider;
import com.android.settingslib.utils.AsyncLoaderCompat;
import com.android.settingslib.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ContextualCardLoader extends AsyncLoaderCompat<List<ContextualCard>> {

    @VisibleForTesting
    static final int DEFAULT_CARD_COUNT = 3;
    @VisibleForTesting
    static final String CONTEXTUAL_CARD_COUNT = "contextual_card_count";
    static final int CARD_CONTENT_LOADER_ID = 1;

    private static final String TAG = "ContextualCardLoader";
    private static final long ELIGIBILITY_CHECKER_TIMEOUT_MS = 250;

    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (isStarted()) {
                mNotifyUri = uri;
                forceLoad();
            }
        }
    };

    @VisibleForTesting
    Uri mNotifyUri;

    private final Context mContext;

    ContextualCardLoader(Context context) {
        super(context);
        mContext = context.getApplicationContext();
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();
        mNotifyUri = null;
        mContext.getContentResolver().registerContentObserver(CardContentProvider.REFRESH_CARD_URI,
                false /*notifyForDescendants*/, mObserver);
        mContext.getContentResolver().registerContentObserver(CardContentProvider.DELETE_CARD_URI,
                false /*notifyForDescendants*/, mObserver);
    }

    @Override
    protected void onStopLoading() {
        super.onStopLoading();
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    protected void onDiscardResult(List<ContextualCard> result) {

    }

    @NonNull
    @Override
    public List<ContextualCard> loadInBackground() {
        final List<ContextualCard> result = new ArrayList<>();
        if (mContext.getResources().getBoolean(R.bool.config_use_legacy_suggestion)) {
            Log.d(TAG, "Skipping - in legacy suggestion mode");
            return result;
        }
        try (Cursor cursor = getContextualCardsFromProvider()) {
            if (cursor.getCount() > 0) {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    final ContextualCard card = new ContextualCard(cursor);
                    if (isLargeCard(card)) {
                        result.add(card.mutate().setIsLargeCard(true).build());
                    } else {
                        result.add(card);
                    }
                }
            }
        }
        return getDisplayableCards(result);
    }

    // Get final displayed cards and log what cards will be displayed/hidden
    @VisibleForTesting
    List<ContextualCard> getDisplayableCards(List<ContextualCard> candidates) {
        final List<ContextualCard> eligibleCards = filterEligibleCards(candidates);
        final List<ContextualCard> stickyCards = new ArrayList<>();
        final List<ContextualCard> visibleCards = new ArrayList<>();
        final List<ContextualCard> hiddenCards = new ArrayList<>();

        final int maxCardCount = getCardCount();
        eligibleCards.forEach(card -> {
            if (card.getCategory() != STICKY_VALUE) {
                return;
            }
            if (stickyCards.size() < maxCardCount) {
                stickyCards.add(card);
            } else {
                hiddenCards.add(card);
            }
        });

        final int nonStickyCardCount = maxCardCount - stickyCards.size();
        eligibleCards.forEach(card -> {
            if (card.getCategory() == STICKY_VALUE) {
                return;
            }
            if (visibleCards.size() < nonStickyCardCount) {
                visibleCards.add(card);
            } else {
                hiddenCards.add(card);
            }
        });
        visibleCards.addAll(stickyCards);

        if (!CardContentProvider.DELETE_CARD_URI.equals(mNotifyUri)) {
            final MetricsFeatureProvider metricsFeatureProvider =
                    FeatureFactory.getFactory(mContext).getMetricsFeatureProvider();

            metricsFeatureProvider.action(mContext,
                    SettingsEnums.ACTION_CONTEXTUAL_CARD_NOT_SHOW,
                    ContextualCardLogUtils.buildCardListLog(hiddenCards));
        }
        return visibleCards;
    }

    @VisibleForTesting
    int getCardCount() {
        // Return the card count if Settings.Global has KEY_CONTEXTUAL_CARD_COUNT key,
        // otherwise return the default one.
        return Settings.Global.getInt(mContext.getContentResolver(),
                CONTEXTUAL_CARD_COUNT, DEFAULT_CARD_COUNT);
    }

    @VisibleForTesting
    Cursor getContextualCardsFromProvider() {
        final ContextualCardFeatureProvider cardFeatureProvider =
                FeatureFactory.getFactory(mContext).getContextualCardFeatureProvider(mContext);
        return cardFeatureProvider.getContextualCards();
    }

    @VisibleForTesting
    List<ContextualCard> filterEligibleCards(List<ContextualCard> candidates) {
        final List<ContextualCard> cards = new ArrayList<>();
        final List<Future<ContextualCard>> eligibleCards = new ArrayList<>();

        for (ContextualCard card : candidates) {
            final EligibleCardChecker checker = new EligibleCardChecker(mContext, card);
            eligibleCards.add(ThreadUtils.postOnBackgroundThread(checker));
        }
        // Collect future and eligible cards
        for (Future<ContextualCard> cardFuture : eligibleCards) {
            try {
                final ContextualCard card = cardFuture.get(ELIGIBILITY_CHECKER_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
                if (card != null) {
                    cards.add(card);
                }
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                Log.w(TAG, "Failed to get eligible state for card, likely timeout. Skipping", e);
            }
        }
        return cards;
    }

    private boolean isLargeCard(ContextualCard card) {
        return card.getSliceUri().equals(CONTEXTUAL_WIFI_SLICE_URI)
                || card.getSliceUri().equals(BLUETOOTH_DEVICES_SLICE_URI)
                || card.getSliceUri().equals(CONTEXTUAL_NOTIFICATION_CHANNEL_SLICE_URI);
    }

    public interface CardContentLoaderListener {
        void onFinishCardLoading(List<ContextualCard> contextualCards);
    }
}