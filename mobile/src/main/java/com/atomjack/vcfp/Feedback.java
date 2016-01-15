package com.atomjack.vcfp;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import com.atomjack.shared.Logger;
import com.atomjack.shared.Preferences;
import com.atomjack.vcfp.activities.MainActivity;

import java.util.HashMap;

public class Feedback implements TextToSpeech.OnInitListener {
	private TextToSpeech feedbackTts = null;
	private TextToSpeech errorsTts = null;
	private Context context;
	private String feedbackQueue = null;
	private String errorsQueue = null;

  private Runnable onFinish = null;

	public Feedback(Context ctx) {
		context = ctx;
	}
  private HashMap<String, String> map = new HashMap<String, String>();
  private int utteranceId = 0;

	@Override
	public void onInit(int i) {
		Logger.d("Feedback onInit");
		if(errorsTts != null)
			errorsTts.setLanguage(VoiceControlForPlexApplication.getVoiceLocale(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.ERRORS_VOICE, "Locale.US")));
		if(feedbackTts != null)
			feedbackTts.setLanguage(VoiceControlForPlexApplication.getVoiceLocale(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.FEEDBACK_VOICE, "Locale.US")));

		if(errorsQueue != null) {
			feedback(errorsQueue, true);
			errorsQueue = null;
		}
		if(feedbackQueue != null) {
			feedback(feedbackQueue, true);
			feedbackQueue = null;
		}

	}

	public void destroy() {
		if(errorsTts != null)
			errorsTts.shutdown();
		if(feedbackTts != null)
			feedbackTts.shutdown();
	}

	public void m(String text, Object... arguments) {
		text = String.format(text, arguments);
		m(text);
	}

	public void e(String text, Object... arguments) {
		text = String.format(text, arguments);
		e(text);
	}

  public void m(String text, Runnable _onFinish) {
    onFinish = _onFinish;
    feedback(text, false);
  }

  public void m(int id, Runnable _onFinish) {
    onFinish = _onFinish;
    feedback(context.getString(id), false);
  }

  public void m(String text) {
    feedback(text, false);
  }

	public void m(int id) {
		feedback(context.getString(id), false);
	}

	public void e(String text) {
		feedback(text, true);
	}

	public void e(int id) {
		feedback(context.getString(id), true);
	}

	public void t(int id) {
		feedback(context.getString(id), true, true);
	}

	protected void feedback(String text, boolean errors) {
		feedback(text, errors, false);
	}

  protected void feedback(String text, boolean errors, boolean forceToast) {

		if(!forceToast && VoiceControlForPlexApplication.getInstance().prefs.get(errors ? Preferences.ERRORS : Preferences.FEEDBACK, MainActivity.FEEDBACK_TOAST) == MainActivity.FEEDBACK_VOICE) {
			TextToSpeech tts = errors ? errorsTts : feedbackTts;
			if (tts == null) {
				// This tts not set up yet, so initiate it and add the text to be spoken to the appropriate queue.
				// The text will be spoken when the tts is finished setting up (in onInit)
				if (errors) {
					errorsTts = new TextToSpeech(context, this);
					errorsQueue = filterText(text);
          errorsTts.setOnUtteranceProgressListener(utteranceProgressListener);
				} else {
					feedbackTts = new TextToSpeech(context, this);
					feedbackQueue = filterText(text);
          feedbackTts.setOnUtteranceProgressListener(utteranceProgressListener);
				}
			} else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          ttsGreater21(tts, text);
        } else {
          ttsUnder20(tts, text);
        }
				if (errors)
					errorsQueue = null;
				else
					feedbackQueue = null;
			}
		} else {
			Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
      if(onFinish != null)
        onFinish.run();
      onFinish = null;
		}
	}

  @SuppressWarnings("deprecation")
  private void ttsUnder20(TextToSpeech tts, String text) {
    map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, Integer.toString(utteranceId++));
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, map);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void ttsGreater21(TextToSpeech tts, String text) {
    String utteranceId = this.hashCode() + "";
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
  }

  // Filter out some extraneous patterns, like when a connection is refused, strip out the uri.
  private String filterText(String text) {
    text = text.replaceAll("to https?://([0-9]+\\.){3}[0-9]+:[0-9]+ refused", " refused");
    return text;
  }

  private UtteranceProgressListener utteranceProgressListener = new UtteranceProgressListener() {
    @Override
    public void onStart(String s) {

    }

    @Override
    public void onDone(String s) {
      if(onFinish != null)
        onFinish.run();
      onFinish = null;
    }

    @Override
    public void onError(String s) {

    }
  };

}
