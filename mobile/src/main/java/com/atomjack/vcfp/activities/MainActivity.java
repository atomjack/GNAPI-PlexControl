package com.atomjack.vcfp.activities;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.atomjack.shared.Logger;
import com.atomjack.shared.SendToDataLayerThread;
import com.atomjack.shared.WearConstants;
import com.atomjack.vcfp.BuildConfig;
import com.atomjack.vcfp.FutureRunnable;
import com.atomjack.shared.Preferences;
import com.atomjack.vcfp.R;
import com.atomjack.vcfp.interfaces.ScanHandler;
import com.atomjack.vcfp.VoiceControlForPlexApplication;
import com.atomjack.vcfp.adapters.MainListAdapter;
import com.atomjack.vcfp.model.MainSetting;
import com.atomjack.vcfp.model.Pin;
import com.atomjack.vcfp.model.PlexClient;
import com.atomjack.vcfp.model.PlexDevice;
import com.atomjack.vcfp.model.PlexServer;
import com.atomjack.vcfp.model.PlexUser;
import com.atomjack.vcfp.net.PlexHttpClient;
import com.atomjack.vcfp.net.PlexHttpUserHandler;
import com.atomjack.vcfp.net.PlexPinResponseHandler;
import com.atomjack.vcfp.services.PlexScannerService;
import com.atomjack.vcfp.tasker.TaskerPlugin;
import com.cubeactive.martin.inscription.WhatsNewDialog;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.wearable.DataMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends VCFPActivity implements TextToSpeech.OnInitListener {

	public final static int FEEDBACK_VOICE = 0;
	public final static int FEEDBACK_TOAST = 1;

	private final static int RESULT_VOICE_FEEDBACK_SELECTED = 0;
	private final static int RESULT_TASKER_PROJECT_IMPORTED = 1;
	private final static int RESULT_SHORTCUT_CREATED = 2;

	private ArrayList<String> availableVoices;
	private boolean settingErrorFeedback = false;

	private FutureRunnable fetchPinTask;

	private PlexServer server = null;
	private PlexClient client = null;

	private TextToSpeech tts;
	private Dialog searchDialog;

	private String authToken;

	AlertDialog.Builder helpDialog;

	MediaRouter mMediaRouter;
	MediaRouterCallback mMediaRouterCallback;
	MediaRouteSelector mMediaRouteSelector;

  // Whether or not we received device logs from a wear device. This will allow a timer to be run in case wear support has
  // been purchased, but no wear device is paired. When this happens, we'll go ahead and email just the mobile device's logs
  //
  private boolean receivedWearLogsResponse = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
    Logger.d("[MainActivity] onCreate");

    // This will enable the UI to be updated (Wear Support hidden/Wear Options shown)
    // once inventory is queried via Google, if wear support has been purchased
    VoiceControlForPlexApplication.getInstance().setOnHasWearActivity(this);

		final WhatsNewDialog whatsNewDialog = new WhatsNewDialog(this);
		whatsNewDialog.show();

		mMediaRouter = MediaRouter.getInstance(getApplicationContext());
		mMediaRouteSelector = new MediaRouteSelector.Builder()
			.addControlCategory(CastMediaControlIntent.categoryForCast(BuildConfig.CHROMECAST_APP_ID))
			.build();
		mMediaRouterCallback = new MediaRouterCallback();
		mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);

		authToken = VoiceControlForPlexApplication.getInstance().prefs.getString(Preferences.AUTHENTICATION_TOKEN);

		setContentView(R.layout.main);

    server = gsonRead.fromJson(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.SERVER, ""), PlexServer.class);
		if(server == null)
			server = new PlexServer(getString(R.string.scan_all));

		client = gsonRead.fromJson(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.CLIENT, ""), PlexClient.class);

		initMainWithServer();
	}

	public void installTasker(MenuItem item) {
		openAppInPlayStore("net.dinglisch.android.taskerm");
	}

	public void installUtter(MenuItem item) {
		openAppInPlayStore("com.brandall.nutter");
	}

	public void installAutoVoice(MenuItem item) {
		openAppInPlayStore("com.joaomgcd.autovoice");
	}

	public void emailDeviceLogs(MenuItem item) {
		if(VoiceControlForPlexApplication.getInstance().hasWear()) {
      receivedWearLogsResponse = false;
      new SendToDataLayerThread(WearConstants.GET_DEVICE_LOGS, this).start();
      Logger.d("requesting device logs from wear device");
      // Now start a 5 second timer. If receivedWearLogsResponse is not true, go ahead and email just the mobile device's log
      final Handler handler = new Handler();
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          if(receivedWearLogsResponse == false)
            emailDeviceLogs("");
        }
      }, 2000);
    } else {
      emailDeviceLogs("");
    }
	}

  // The passed 'wearLog' string is the contents of the wear device's log. If there is no wear device paired with the mobile device,
  // the passed string will be empty ("")
  //
	public void emailDeviceLogs(final String wearLog) {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
          boolean hasPermission = (ContextCompat.checkSelfPermission(MainActivity.this,
                  Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
          if(!hasPermission) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
          } else {
            Logger.d("Emailing device logs");
            Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Voice Control for Plex Android Logs");
            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Build the body of the email
            StringBuilder body = new StringBuilder();
            body.append(String.format("Manufacturer: %s\n", Build.MANUFACTURER));
            body.append(String.format("Device: %s\n", Build.DEVICE));
            body.append(String.format("Model: %s\n", Build.MODEL));
            body.append(String.format("Product: %s\n", Build.PRODUCT));
            body.append(String.format("Version: %s\n", Build.VERSION.RELEASE));
            body.append(String.format("App Version: %s\n\n", getPackageManager().getPackageInfo(getPackageName(), 0).versionName));

            body.append(String.format("Logged in: %s\n\n", VoiceControlForPlexApplication.getInstance().prefs.getString(Preferences.PLEX_USERNAME) != null ? "yes" : "no"));

            body.append("Description of the issue:\n\n");

            emailIntent.setType("application/octet-stream");

            emailIntent.putExtra(Intent.EXTRA_TEXT, body.toString());

            File tempDirectory = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/tmp");
            if (!tempDirectory.exists())
              tempDirectory.mkdirs();

            File tempFile = new File(tempDirectory, "/vcfp-log.txt");
            FileOutputStream fos = new FileOutputStream(tempFile);
            Writer out = new OutputStreamWriter(fos, "UTF-8");

            Process process = Runtime.getRuntime().exec("logcat -d *:V");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
              log.append(line);
              log.append(System.getProperty("line.separator"));
            }

            bufferedReader.close();

            out.write(log.toString());
            out.flush();
            out.close();

            ArrayList<Uri> uris = new ArrayList<Uri>();
            uris.add(Uri.parse("file://" + tempFile.getAbsolutePath()));
//            uris.add(FileProvider.getUriForFile(MainActivity.this, "com.atomjack.vcfp.fileprovider", tempFile));

            if (!wearLog.equals("")) {
              Logger.d("attaching wear log");
              tempFile = new File(tempDirectory, "/vcfp-wear-log.txt");
              fos = new FileOutputStream(tempFile);
              out = new OutputStreamWriter(fos, "UTF-8");
              out.write(wearLog);
              out.flush();
              out.close();
              uris.add(Uri.parse("file://" + tempFile.getAbsolutePath()));
//                uris.add(FileProvider.getUriForFile(MainActivity.this, "com.atomjack.vcfp.fileprovider", tempFile));
            }
            emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            startActivity(emailIntent);
          }
				} catch (final Exception ex) {
          Logger.e("Exception emailing device logs: %s", ex);
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              feedback.e("Error emailing device logs: %s", ex.getMessage());
            }
          });
        }
        return null;
			}
		}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	}

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    switch (requestCode) {
      case REQUEST_WRITE_STORAGE: {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
        {
          emailDeviceLogs("");
        } else
        {
          feedback.e(R.string.email_device_logs_write_storage_denied);
        }
      }
    }
  }

  public void showChangelog(MenuItem item) {
		final WhatsNewDialog whatsNewDialog = new WhatsNewDialog(this);
		whatsNewDialog.forceShow();
	}

	private void openAppInPlayStore(String packageName) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
		} catch (android.content.ActivityNotFoundException anfe) {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
		}
	}

	private boolean hasValidTasker() {
		PackageInfo pinfo;
		try
		{
			pinfo = getPackageManager().getPackageInfo("net.dinglisch.android.tasker", 0);
			return true;
		} catch(Exception e) {}
		try
		{
			pinfo = getPackageManager().getPackageInfo("net.dinglisch.android.taskerm", 0);
			return true;
		} catch(Exception e) {
			Logger.d("Exception getting google search version: " + e.getStackTrace());
		}
		return false;
	}

	private boolean hasValidAutoVoice() {
		try
		{
			if(hasValidTasker()) {
				PackageInfo pinfo = getPackageManager().getPackageInfo("com.joaomgcd.autovoice", 0);
				return true;
			}
		} catch(Exception e) {
			Logger.d("Exception getting autovoice version: " + e.getStackTrace());
		}
		return false;
	}

	private boolean hasValidUtter() {
		try
		{
			if(hasValidTasker()) {
				PackageInfo pinfo = getPackageManager().getPackageInfo("com.brandall.nutter", 0);
				return true;
			}
		} catch(Exception e) {
			Logger.d("Exception getting utter version: " + e.getStackTrace());
		}
		return false;
	}

	public void resumeChecked(View v) {
    Logger.d("Setting resume checkbox to %s", ((CheckBox) v).isChecked());
    VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.RESUME, ((CheckBox) v).isChecked());
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == RESULT_VOICE_FEEDBACK_SELECTED) {
			if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
				// success, create the TTS instance
				availableVoices = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES);
				// Need this or else voice selection won't show up:
				tts = new TextToSpeech(this, this);
			} else {
				// missing data, install it
				Intent installIntent = new Intent();
				installIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
				startActivity(installIntent);
			}
		} else if(requestCode == RESULT_TASKER_PROJECT_IMPORTED) {
			AlertDialog.Builder usageDialog = new AlertDialog.Builder(MainActivity.this);
			usageDialog.setTitle(R.string.import_tasker_project);
			usageDialog.setMessage(R.string.import_tasker_instructions);
			usageDialog.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.dismiss();
					Intent i;
					PackageManager manager = getPackageManager();
					try {
						i = manager.getLaunchIntentForPackage("net.dinglisch.android.tasker");
						if (i == null)
							throw new PackageManager.NameNotFoundException();
						i.addCategory(Intent.CATEGORY_LAUNCHER);
						startActivity(i);
					} catch (PackageManager.NameNotFoundException e) {

					}
				}
			});
			usageDialog.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.dismiss();
				}
			});
			usageDialog.show();
		} else if(requestCode == RESULT_SHORTCUT_CREATED) {
			if(resultCode == RESULT_OK) {
				data.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
				sendBroadcast(data);

				feedback.m(getString(R.string.shortcut_created));
			}
		}
	}

	private void initMainWithServer() {
    String clientName = client != null ? client.name : getString(R.string.not_set);
    if(currentNetworkState == NetworkState.MOBILE)
      clientName = getString(R.string.this_device);
		MainSetting setting_data[] = new MainSetting[] {
			new MainSetting(MainListAdapter.SettingHolder.TAG_SERVER, getResources().getString(R.string.stream_video_from_server), server.owned ? server.name : server.sourceTitle),
			new MainSetting(MainListAdapter.SettingHolder.TAG_CLIENT, String.format(getResources().getString(R.string.your_current_default_client_is), clientName), getString(R.string.tap_cast_icon_to_change_default_client)),
			new MainSetting(MainListAdapter.SettingHolder.TAG_FEEDBACK, getResources().getString(R.string.feedback), VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.FEEDBACK, FEEDBACK_TOAST) == FEEDBACK_VOICE ? getResources().getString(R.string.voice) : getResources().getString(R.string.toast)),
			new MainSetting(MainListAdapter.SettingHolder.TAG_ERRORS, getResources().getString(R.string.errors), VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.ERRORS, FEEDBACK_TOAST) == FEEDBACK_VOICE ? getResources().getString(R.string.voice) : getResources().getString(R.string.toast))
		};

		MainListAdapter adapter = new MainListAdapter(this, R.layout.main_setting_item_row, setting_data);

		ListView settingsList = (ListView)findViewById(R.id.settingsList);
		settingsList.setFooterDividersEnabled(true);
		settingsList.addFooterView(new View(settingsList.getContext()));
		settingsList.setAdapter(adapter);
		setListViewHeightBasedOnChildren(settingsList);
		settingsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> adapter, View view, int position,
															long arg3) {
				MainListAdapter.SettingHolder holder = (MainListAdapter.SettingHolder) view.getTag();
				Logger.d("Clicked %s", holder.tag);
				if (holder.tag.equals(holder.TAG_SERVER)) {
					if(currentNetworkState.equals(NetworkState.DISCONNECTED)) {
						feedback.e(R.string.no_wifi_connection_message);
						return;
					}

          LayoutInflater inflater = getLayoutInflater();
          View layout = inflater.inflate(R.layout.search_popup, null);

          AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);

          builder.setView(layout);
          builder.setTitle(getResources().getString(R.string.searching_for_plex_servers));

          builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
              serverScanCanceled = true;
            }
          });
          searchDialog = builder.create();
          searchDialog.show();
          Intent scannerIntent = new Intent(MainActivity.this, PlexScannerService.class);
          scannerIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
          scannerIntent.putExtra(PlexScannerService.CLASS, MainActivity.class);
          scannerIntent.setAction(PlexScannerService.ACTION_SCAN_SERVERS);
          startService(scannerIntent);
				} else if (holder.tag.equals(holder.TAG_FEEDBACK)) {
					selectFeedback();
				} else if (holder.tag.equals(holder.TAG_ERRORS)) {
					selectFeedback(true);
				}
			}
		});

		CheckBox resumeCheckbox = (CheckBox)findViewById(R.id.resumeCheckbox);
		resumeCheckbox.setChecked(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.RESUME, false));
	}

	public void settingRowHelpButtonClicked(View v) {
		String helpButtonClicked = v.getTag().toString();
		if(helpDialog == null) {
			helpDialog = new AlertDialog.Builder(MainActivity.this);
		}
		helpDialog.setTitle(R.string.app_name);

		if(helpButtonClicked.equals("server")) {
			helpDialog.setMessage(R.string.help_server);
		} else if(helpButtonClicked.equals("client")) {
			helpDialog.setMessage(R.string.help_client);
		} else if(helpButtonClicked.equals("feedback")) {
			helpDialog.setMessage(R.string.help_feedback);
		} else if(helpButtonClicked.equals("errors")) {
			helpDialog.setMessage(R.string.help_errors);
		}
		helpDialog.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
			}
		});
		helpDialog.show();
	}

	private void selectFeedback() {
		selectFeedback(false);
	}

	private void selectFeedback(final boolean errors) {
		AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
		builder.setTitle(errors ? R.string.errors : R.string.feedback);
		final MainActivity ctx = this;
		builder.setPositiveButton(R.string.feedback_voice, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
        VoiceControlForPlexApplication.getInstance().prefs.put(errors ? Preferences.ERRORS : Preferences.FEEDBACK, FEEDBACK_VOICE);
				Intent checkIntent = new Intent();
				checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
				tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
					@Override
					public void onInit(int i) {}
				});
				String engine = tts.getDefaultEngine();
				if (engine != null)
					checkIntent.setPackage(engine);
				settingErrorFeedback = errors;
				startActivityForResult(checkIntent, RESULT_VOICE_FEEDBACK_SELECTED);
				initMainWithServer();
			}
		}).setNegativeButton(R.string.feedback_toast, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        VoiceControlForPlexApplication.getInstance().prefs.put(errors ? Preferences.ERRORS : Preferences.FEEDBACK, FEEDBACK_TOAST);
        initMainWithServer();
      }
    });
		AlertDialog d = builder.create();
		d.show();
	}

	public void installShortcut(MenuItem item) {
		Intent intent = new Intent(this, ShortcutProviderActivity.class);

		startActivityForResult(intent, RESULT_SHORTCUT_CREATED);
	}

	public void showAbout(MenuItem item) {
		AlertDialog.Builder alertDialog = new AlertDialog.Builder(MainActivity.this)
			.setTitle(R.string.app_name)
			.setMessage(R.string.about_text);

		alertDialog.show();
	}

	public void donate(MenuItem item) {
		Intent intent = new Intent(Intent.ACTION_VIEW,
			Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=UJF9QY9QELERG"));
		startActivity(intent);
	}

	public void logout(MenuItem item) {
    VoiceControlForPlexApplication.getInstance().prefs.remove(Preferences.AUTHENTICATION_TOKEN);
    VoiceControlForPlexApplication.getInstance().prefs.remove(Preferences.PLEX_USERNAME);
		authToken = null;

		// If the currently selected server is not local, reset it to scan all.
		if(!server.local) {
			server = new PlexServer(getString(R.string.scan_all));
			saveSettings();
			initMainWithServer();
		}

		// Remove any non-local servers from our list
		for(PlexServer s : VoiceControlForPlexApplication.servers.values()) {
			if(!s.local)
				VoiceControlForPlexApplication.servers.remove(s.name);
		}

    VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.SAVED_SERVERS, gsonWrite.toJson(VoiceControlForPlexApplication.servers));
		MenuItem loginItem = menu.findItem(R.id.menu_login);
		loginItem.setVisible(true);
		MenuItem logoutItem = menu.findItem(R.id.menu_logout);
		logoutItem.setVisible(false);

		feedback.m(R.string.logged_out);
	}

  public void cinemaTrailers(MenuItem item) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.cinema_trailers_title));
    final CharSequence[] items = {getString(R.string.none), "1", "2", "3", "4", "5"};
    int numTrailers = VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.NUM_CINEMA_TRAILERS, 0);
    builder.setSingleChoiceItems(items, numTrailers, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        Logger.d("clicked %d", which);
        VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.NUM_CINEMA_TRAILERS, which);
        dialog.dismiss();
      }
    });
    builder.create().show();
  }

  public void purchaseWear(MenuItem item) {
    showWearPurchaseRequired();
  }

	public void showLogin(MenuItem item) {
		showLogin();
	}

	public void showLogin() {
		PlexHttpClient.getPinCode(new PlexPinResponseHandler() {
      @Override
      public void onSuccess(Pin pin) {
        showPin(pin);
      }

      @Override
      public void onFailure(Throwable error) {
        error.printStackTrace();
        feedback.e(R.string.login_error);
      }
    });
	}

	private void switchLogin() {
		menu.findItem(R.id.menu_login).setVisible(!menu.findItem(R.id.menu_login).isVisible());
		menu.findItem(R.id.menu_logout).setVisible(!menu.findItem(R.id.menu_logout).isVisible());
	}

	private void showPin(final Pin pin) {
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(MainActivity.this);
		alertDialogBuilder.setTitle(R.string.pin_title);
		alertDialogBuilder.setMessage(String.format(getString(R.string.pin_message), pin.code));
		alertDialogBuilder
			.setCancelable(false)
			.setNegativeButton("Cancel",
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
						fetchPinTask.getFuture().cancel(false);
					}
				}
			)
			.setNeutralButton("Manual", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, int id) {
				dialog.dismiss();
				fetchPinTask.getFuture().cancel(false);
				showManualLogin();
				}
			});


		// create and show an alert dialog
		final AlertDialog pinAlert = alertDialogBuilder.create();
		pinAlert.show();

		// Now set up a task to hit the below url (based on the "id" field returned in the above http POST)
		// every second. Once the user has entered the code on the plex website, the xml returned from the
		// below http GET will contain their authentication token. Once that is retrieved, save it, switch
		// the login/logout buttons in the menu, and cancel the dialog.
		final Context context = MainActivity.this;
		fetchPinTask = new FutureRunnable() {
			@Override
			public void run() {
				PlexHttpClient.fetchPin(pin.id, new PlexPinResponseHandler() {
          @Override
          public void onSuccess(Pin pin) {
            if(pin.authToken != null) {
              authToken = pin.authToken;
              VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.AUTHENTICATION_TOKEN, authToken);
              PlexHttpClient.signin(authToken, new PlexHttpUserHandler() {
                @Override
                public void onSuccess(PlexUser user) {
                  Logger.d("Got user: %s", user);
                  VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.PLEX_USERNAME, user.username);
                  Logger.d("Saved username %s", user.username);
                }

                @Override
                public void onFailure(int statusCode) {
                  // TODO: Handle failure
                }
              });
              pinAlert.cancel();
              Handler mainHandler = new Handler(context.getMainLooper());
              mainHandler.post(new Runnable() {
                @Override
                public void run() {
                  feedback.m(R.string.logged_in);
                  switchLogin();
                  PlexScannerService.refreshResources(authToken, new PlexScannerService.RefreshResourcesResponseHandler() {
                    @Override
                    public void onSuccess() {
                      feedback.t(R.string.servers_refreshed);
                    }

                    @Override
                    public void onFailure(int statusCode) {
                      feedback.e(R.string.remote_scan_error);
                    }
                  });
                }
              });
              // We got the auth token, so cancel this task
              getFuture().cancel(false);
            }
          }

          @Override
          public void onFailure(Throwable error) {
            error.printStackTrace();
          }
        });
			}
		};
		// Set up the schedule service and let fetchPinTask know of the Future object, so the task can cancel
		// itself once the authentication token is retrieved.
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		Future<?> future = executor.scheduleAtFixedRate(fetchPinTask, 0, 1000, TimeUnit.MILLISECONDS);
		fetchPinTask.setFuture(future);
	}

	private void showManualLogin() {
		LayoutInflater layoutInflater = LayoutInflater.from(MainActivity.this);
		View promptView = layoutInflater.inflate(R.layout.login, null);
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(MainActivity.this);
		alertDialogBuilder.setView(promptView);
		alertDialogBuilder.setTitle(R.string.login_title);
		alertDialogBuilder.setMessage(R.string.login_message);
		final EditText usernameInput = (EditText) promptView.findViewById(R.id.usernameInput);
		final EditText passwordInput = (EditText) promptView.findViewById(R.id.passwordInput);
		alertDialogBuilder
			.setCancelable(true)
			.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, int id) {
				}
			})
			.setNeutralButton(R.string.button_pin, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, int id) {
				dialog.dismiss();
				showLogin();
				}
			})
			.setNegativeButton(R.string.cancel,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int id) {
									dialog.cancel();
								}
							}
			);


		// create an alert dialog
		final AlertDialog alertD = alertDialogBuilder.create();

		alertD.show();

		Button b = alertD.getButton(DialogInterface.BUTTON_POSITIVE);
		b.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
        PlexHttpClient.signin(usernameInput.getText().toString(), passwordInput.getText().toString(), new PlexHttpUserHandler() {
          @Override
          public void onSuccess(PlexUser user) {
            VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.AUTHENTICATION_TOKEN, user.authenticationToken);
            authToken = user.authenticationToken;
            VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.PLEX_USERNAME, user.username);
            feedback.m(R.string.logged_in);
            MenuItem loginItem = menu.findItem(R.id.menu_login);
            loginItem.setVisible(false);
            MenuItem logoutItem = menu.findItem(R.id.menu_logout);
            logoutItem.setVisible(true);
            alertD.cancel();
          }

          @Override
          public void onFailure(int statusCode) {
            Logger.e("Failure logging in");
            String err = getString(R.string.login_error);
            if(statusCode == 401) {
              err = getString(R.string.login_incorrect);
            }
            feedback.e(err);
						alertD.cancel();
          }
        });
			}
		});
	}

	public void importTaskerProject(MenuItem item) {
		String xmlfile = "VoiceControlForPlex.prj.xml";

		try
		{
			AssetManager am = getAssets();
			InputStream is = am.open(xmlfile);
			int size = is.available();
			byte[] buffer = new byte[size];
			is.read(buffer);
			is.close();

			String xmlContents = new String(buffer);
			xmlContents = xmlContents.replace("%RECOGNITION_REGEX%", getString(R.string.pattern_recognition));
			buffer = xmlContents.getBytes();
			Logger.d("directory: %s", Environment.getExternalStorageDirectory());

			File f = new File(Environment.getExternalStorageDirectory() + "/" + xmlfile);
			FileOutputStream fos = new FileOutputStream(f);
			fos.write(buffer);
			fos.close();

			Logger.d("Wrote xml file");

			Intent i = new Intent();
			i.setAction(Intent.ACTION_VIEW);
			i.setDataAndType(Uri.fromFile(f), "text/xml");
			startActivityForResult(i, RESULT_TASKER_PROJECT_IMPORTED);
		} catch (Exception e) {
			Logger.d("Exception opening tasker profile xml: ");
			e.printStackTrace();
			return;
		}


	}

	public void showUsageExamples(View v) {
		AlertDialog.Builder usageDialog = new AlertDialog.Builder(MainActivity.this);
		usageDialog.setTitle(R.string.help_usage_button);
		usageDialog.setMessage(R.string.help_usage);
		usageDialog.setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
			}
		});
		usageDialog.show();
	}

	/**** Method for Setting the Height of the ListView dynamically.
	 **** Hack to fix the issue of not showing all the items of the ListView
	 **** when placed inside a ScrollView  ****/
	public static void setListViewHeightBasedOnChildren(ListView listView) {
		ListAdapter listAdapter = listView.getAdapter();
		if (listAdapter == null)
			return;

		int desiredWidth = View.MeasureSpec.makeMeasureSpec(listView.getWidth(), View.MeasureSpec.UNSPECIFIED);
		int totalHeight = 0;
		View view = null;
		for (int i = 0; i < listAdapter.getCount(); i++) {
			view = listAdapter.getView(i, view, listView);
			if (i == 0)
				view.setLayoutParams(new ViewGroup.LayoutParams(desiredWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

			view.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED);
			totalHeight += view.getMeasuredHeight();
		}
		ViewGroup.LayoutParams params = listView.getLayoutParams();
		params.height = totalHeight + (listView.getDividerHeight() * (listAdapter.getCount() - 1));
		listView.setLayoutParams(params);
		listView.requestLayout();
	}

	@Override
	protected void onNewIntent(Intent intent) {
    Logger.d("MainActivity onNewIntent: %s", intent.getAction());

      if(intent.getAction().equals(PlexScannerService.ACTION_SERVER_SCAN_FINISHED)) {
        if(serverScanCanceled) {
          serverScanCanceled = false;
          return;
        }
        Logger.d("Got " + VoiceControlForPlexApplication.servers.size() + " servers");
        if(searchDialog != null)
          searchDialog.dismiss();
        VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.SAVED_SERVERS, gsonWrite.toJson(VoiceControlForPlexApplication.servers));

        if(intent.getBooleanExtra(com.atomjack.shared.Intent.EXTRA_SILENT, false) == false) {
          if (VoiceControlForPlexApplication.servers.size() > 0) {
            showPlexServers(VoiceControlForPlexApplication.servers, new ScanHandler() {
              @Override
              public void onDeviceSelected(PlexDevice device, boolean resume) {
                if(device instanceof PlexServer)
                  setServer((PlexServer) device);
                else if(device instanceof PlexClient)
                  setClient((PlexClient)device);
              }
            });
          } else {
            searchDialog.dismiss();

            if(intent.getBooleanExtra(PlexScannerService.REMOTE_SERVER_SCAN_UNAUTHORIZED, false) == true) {
              AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
              builder.setTitle(R.string.remote_scan_unauthorized);
              builder.setMessage(R.string.remote_scan_unauthorized_message);
              builder.setCancelable(false).setNeutralButton(R.string.no_thanks, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                  dialog.cancel();
                }
              });
              builder.setCancelable(false).setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                  logout(null);
                  showLogin();
                  dialog.cancel();
                }
              });
              AlertDialog d = builder.create();
              d.show();
            } else {
              AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
              builder.setTitle(R.string.no_servers_found);
              if(VoiceControlForPlexApplication.getInstance().unauthorizedLocalServersFound.size() > 0) {
                if (VoiceControlForPlexApplication.getInstance().isLoggedIn())
                  builder.setMessage(R.string.unauthorized_local_server_found_logged_in);
                else
                  builder.setMessage(R.string.unauthorized_local_server_found_logged_out);
              }

              builder.setCancelable(false).setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                  dialog.cancel();
                }
              });
              AlertDialog d = builder.create();

              d.show();
            }
          }
        }
      } else if(intent.getStringExtra(com.atomjack.shared.Intent.SCAN_TYPE) != null && intent.getStringExtra(com.atomjack.shared.Intent.SCAN_TYPE).equals(com.atomjack.shared.Intent.SCAN_TYPE_CLIENT)) {
        Logger.d("clientScanCanceled: %s", clientScanCanceled);
        if(clientScanCanceled) {
          clientScanCanceled = false;
          return;
        }
        ArrayList<PlexClient> clients = intent.getParcelableArrayListExtra(com.atomjack.shared.Intent.EXTRA_CLIENTS);
        if(clients != null || (VoiceControlForPlexApplication.getInstance().castClients != null && VoiceControlForPlexApplication.getInstance().castClients.size() > 0)) {
          VoiceControlForPlexApplication.clients = new HashMap<>();
          if(clients != null)
            for (PlexClient c : clients) {
              VoiceControlForPlexApplication.clients.put(c.name, c);
            }
          VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.SAVED_CLIENTS, gsonWrite.toJson(VoiceControlForPlexApplication.clients));
          boolean showConnectToClients = intent.getBooleanExtra(com.atomjack.shared.Intent.EXTRA_CONNECT_TO_CLIENT, false);
          Logger.d("showConnectToClients: %s", showConnectToClients);
          clientScanCanceled = false;
          showPlexClients(false, onClientChosen);
        } else {
          AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
          builder.setTitle(R.string.no_clients_found);
          builder.setCancelable(false)
            .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
              }
            });
          AlertDialog d = builder.create();
          d.show();
        }
      } else if(intent.getAction() != null && intent.getAction().equals(com.atomjack.shared.Intent.SHOW_WEAR_PURCHASE)) {
        // An Android Wear device was successfully pinged, so show popup alerting the
        // user that they can purchase wear support, but only if we've never shown the popup before.
        if(VoiceControlForPlexApplication.getInstance().hasWear()) {
          hidePurchaseWearMenuItem();
        } else {
          if (VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.HAS_SHOWN_WEAR_PURCHASE_POPUP, false) == false)
            showWearPurchase();
        }
      } else if(intent.getAction() != null && intent.getAction().equals(com.atomjack.shared.Intent.SHOW_WEAR_PURCHASE_REQUIRED)) {
        showWearPurchaseRequired();
      } else if(intent.getAction() != null && intent.getAction().equals(WearConstants.GET_DEVICE_LOGS)) {
        String wearLog = intent.getStringExtra(WearConstants.LOG_CONTENTS);
        receivedWearLogsResponse = true;
        emailDeviceLogs(wearLog);
      }
		//}
		super.onNewIntent(intent);
	}


  @Override
	protected void setServer(PlexServer _server) {
		Logger.d("Setting Server %s with %d connections.", _server.name, server.connections.size());
		server = _server;
		saveSettings();

		if(client == null) {
			searchForPlexClients();
		} else {
			initMainWithServer();
		}

	}

  @Override
  protected void setClient(PlexClient _client) {
    super.setClient(_client);
    Logger.d("[MainActivity] setClient");
    if(!VoiceControlForPlexApplication.getInstance().hasChromecast() && _client.isCastClient) {
      showChromecastPurchase(_client, new Runnable() {
        @Override
        public void run() {
          // Chromecast purchase was successful, and mHasChromecast was set to true, so call this again to continue
          setClient(postChromecastPurchaseClient);
        }
      });
    } else {
      client = _client;
      Logger.d("Selected mClient: %s", client);
      saveSettings();
      initMainWithServer();
    }
	}

	private void saveSettings() {
    VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.SERVER, gsonWrite.toJson(server));
    VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.CLIENT, gsonWrite.toJson(client));
    VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.RESUME, VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.RESUME, false));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu _menu) {
    super.onCreateOptionsMenu(_menu);
    getMenuInflater().inflate(R.menu.menu_main, _menu);
		menu = _menu;

    if(plexSubscription.isSubscribed() || castPlayerManager.isSubscribed())
      setCastIconActive();

    if(VoiceControlForPlexApplication.getInstance().hasWear())
      hidePurchaseWearMenuItem();

		if(VoiceControlForPlexApplication.getInstance().hasChromecast()) {
			MenuItem chromecastOptionsItem = menu.findItem(R.id.menu_chromecast_video);
			chromecastOptionsItem.setVisible(true);
		}

		if(authToken != null) {
			_menu.findItem(R.id.menu_login).setVisible(false);
			_menu.findItem(R.id.menu_logout).setVisible(true);
		}
		if (!hasValidAutoVoice() && !hasValidUtter()) {
			_menu.findItem(R.id.menu_tasker_import).setVisible(false);
			if (!hasValidTasker()) {
				_menu.findItem(R.id.menu_install_tasker).setVisible(true);
			}
			if (!hasValidUtter()) {
				_menu.findItem(R.id.menu_install_utter).setVisible(true);
			}
			if (!hasValidAutoVoice()) {
				_menu.findItem(R.id.menu_install_autovoice).setVisible(true);
			}
		}
    return true;
	}





	@Override
	protected void onDestroy() {
		Logger.d("MainActivity onDestroy");

		feedback.destroy();
		if(tts != null)
			tts.shutdown();
		super.onDestroy();
	}


	@Override
	protected void onPause() {
    Logger.d("[MainActivity] onPause");
		if (isFinishing()) {
			mMediaRouter.removeCallback(mMediaRouterCallback);
		}
		VoiceControlForPlexApplication.applicationPaused();
		super.onPause();
	}

	@Override
	protected void onResume() {
//		Logger.d("MainActivity onResume");
		super.onResume();
		VoiceControlForPlexApplication.applicationResumed();
    plexSubscription.setListener(this);
    castPlayerManager.setListener(this);
	}

	@Override
	public void finish() {
		if(getCallingActivity() != null)
		{
			Bundle bundle = new Bundle();
			// Pass the entire string of what was said into autovoice
			bundle.putString(com.atomjack.shared.Intent.ARGUMENTS, "%avcomm");
			if (TaskerPlugin.Setting.hostSupportsOnFireVariableReplacement(this))
				TaskerPlugin.Setting.setVariableReplaceKeys(bundle, new String[]{com.atomjack.shared.Intent.ARGUMENTS});

			Intent i = new Intent();
			i.putExtras(bundle);

			String blurb = "Server: " + (server != null ? server.name : getResources().getString(R.string.scan_all));
			blurb += " | Client: " + (client != null ? client.name : "Not specified.");
			if(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.RESUME, false))
				blurb += " (resume)";


			i.putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb);
			setResult(Activity.RESULT_OK, i);
		}
		super.finish();
	}

	@Override
	public void onInit(int status) {
		if (status == TextToSpeech.SUCCESS) {
			final String pref = settingErrorFeedback ? Preferences.ERRORS_VOICE : Preferences.FEEDBACK_VOICE;
			if (availableVoices != null) {
				AlertDialog.Builder adb = new AlertDialog.Builder(this);
				final CharSequence items[] = availableVoices.toArray(new CharSequence[availableVoices.size()]);
				int selectedVoice = -1;
				String v = VoiceControlForPlexApplication.getInstance().prefs.get(pref, "Locale.US");
				if (availableVoices.indexOf(v) > -1)
					selectedVoice = availableVoices.indexOf(v);

				adb.setSingleChoiceItems(items, selectedVoice, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface d, int n) {
            VoiceControlForPlexApplication.getInstance().prefs.put(pref, items[n].toString());
						d.dismiss();
					}
				});
				adb.setNegativeButton(R.string.cancel, null);
				adb.setTitle(R.string.select_voice);
				adb.show();
			} else {
        VoiceControlForPlexApplication.getInstance().prefs.put(pref, "Locale.US");
			}
		}
	}

	private class MediaRouterCallback extends MediaRouter.Callback {
    @Override
    public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo route) {
      super.onRouteRemoved(router, route);
      if(VoiceControlForPlexApplication.castClients.containsKey(route.getName())) {
        Logger.d("Cast Client %s has gone missing. Removing.", route.getName());
        VoiceControlForPlexApplication.castClients.remove(route.getName());
        VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.SAVED_CAST_CLIENTS, gsonWrite.toJson(VoiceControlForPlexApplication.castClients));
        // If the "select a plex client" dialog is showing, refresh the list of clients
        // TODO: Refresh device dialog if needed
//        if(localScan.isDeviceDialogShowing()) {
//          localScan.deviceSelectDialogRefresh();
//        }
      }
    }

    @Override
		public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route)
		{
			Logger.d("onRouteAdded: %s", route);
			if(!VoiceControlForPlexApplication.castClients.containsKey(route.getName())) {
				VoiceControlForPlexApplication.castClients.remove(route.getName());
			}
      PlexClient client = new PlexClient();
      client.isCastClient = true;
      client.name = route.getName();
      client.product = route.getDescription();
      client.castDevice = CastDevice.getFromBundle(route.getExtras());
      client.machineIdentifier = client.castDevice.getDeviceId();
      VoiceControlForPlexApplication.castClients.put(client.name, client);
      Logger.d("Added cast client %s (%s)", client.name, client.machineIdentifier);
      VoiceControlForPlexApplication.getInstance().prefs.put(Preferences.SAVED_CAST_CLIENTS, gsonWrite.toJson(VoiceControlForPlexApplication.castClients));
      // If the "select a plex client" dialog is showing, refresh the list of clients
      if(deviceSelectDialog != null && deviceSelectDialog.isShowing()) {
        deviceSelectDialogRefresh();
      }
		}

		@Override
		public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo route) {
			Logger.d("onRouteSelected: %s", route);
		}

		@Override
		public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo route) {
			Logger.d("onRouteUnselected: %s", route);
		}
	}

  @Override
  public void onConnected(int connectionType) {
    super.onConnected(connectionType);
    initMainWithServer();
  }

  @Override
  public void onDisconnected() {
    super.onDisconnected();
  }

  @Override
  public void hidePurchaseWearMenuItem() {
    MenuItem wearItem = menu.findItem(R.id.menu_purchase_wear);
    wearItem.setVisible(false);
    MenuItem wearOptionsItem = menu.findItem(R.id.menu_wear_options);
    wearOptionsItem.setVisible(true);

  }

  public void showWearOptions(MenuItem item) {
    AlertDialog.Builder chooserDialog = new AlertDialog.Builder(this);
    chooserDialog.setTitle(R.string.wear_primary_function);
    chooserDialog.setMessage(R.string.wear_primary_function_option_description);
    chooserDialog.setPositiveButton(R.string.voice_input, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        DataMap dataMap = new DataMap();
        dataMap.putBoolean(WearConstants.PRIMARY_FUNCTION_VOICE_INPUT, true);
        new SendToDataLayerThread(WearConstants.SET_WEAR_OPTIONS, dataMap, MainActivity.this).start();
        dialog.dismiss();
      }
    });
    chooserDialog.setNeutralButton(R.string.play_pause, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				DataMap dataMap = new DataMap();
				dataMap.putBoolean(WearConstants.PRIMARY_FUNCTION_VOICE_INPUT, false);
				new SendToDataLayerThread(WearConstants.SET_WEAR_OPTIONS, dataMap, MainActivity.this).start();
				dialog.dismiss();
			}
		});
    chooserDialog.show();
  }

	public void showChromecastVideoOptions(MenuItem item) {
		AlertDialog.Builder chooserDialog = new AlertDialog.Builder(this);
		chooserDialog.setTitle(R.string.chromecast_video_options_header);
//		chooserDialog.setMessage(R.string.wear_primary_function_option_description);
		chooserDialog.setPositiveButton(R.string.chromecast_video_local, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				showChromecastVideoOptions(true);
				dialog.dismiss();
			}
		});
		chooserDialog.setNeutralButton(R.string.chromecast_video_remote, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				showChromecastVideoOptions(false);
				dialog.dismiss();
			}
		});
		chooserDialog.show();
	}

	private void showChromecastVideoOptions(final boolean local) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(local ? getString(R.string.chromecast_video_local_full) : getString(R.string.chromecast_video_remote_full));
		final CharSequence[] items = VoiceControlForPlexApplication.chromecastVideoOptions.keySet().toArray(new CharSequence[VoiceControlForPlexApplication.chromecastVideoOptions.size()]);
		int videoQuality = new ArrayList<>(VoiceControlForPlexApplication.chromecastVideoOptions.keySet()).indexOf(VoiceControlForPlexApplication.getInstance().prefs.getString(local ? Preferences.CHROMECAST_VIDEO_QUALITY_LOCAL : Preferences.CHROMECAST_VIDEO_QUALITY_REMOTE));
		if(videoQuality == -1)
			videoQuality = new ArrayList<>(VoiceControlForPlexApplication.chromecastVideoOptions.keySet()).indexOf("8mbps 1080p");
		Logger.d("video quality: %d", videoQuality);
		builder.setSingleChoiceItems(items, videoQuality, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				Logger.d("clicked %d (%s) (%s)", which, (String)items[which], local);
				VoiceControlForPlexApplication.getInstance().prefs.put(local ? Preferences.CHROMECAST_VIDEO_QUALITY_LOCAL : Preferences.CHROMECAST_VIDEO_QUALITY_REMOTE, (String)items[which]);
				dialog.dismiss();
			}
		});
		builder.create().show();
	}


}


