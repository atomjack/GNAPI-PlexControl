package com.atomjack.vcfp.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.RecognizerIntent;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;

import com.atomjack.shared.SendToDataLayerThread;
import com.atomjack.shared.WearConstants;
import com.atomjack.vcfp.Utils;
import com.atomjack.vcfp.interfaces.ActiveConnectionHandler;
import com.atomjack.vcfp.interfaces.AfterTransientTokenRequest;
import com.atomjack.vcfp.BuildConfig;
import com.atomjack.vcfp.CastPlayerManager;
import com.atomjack.vcfp.Feedback;
import com.atomjack.shared.Logger;
import com.atomjack.vcfp.PlexHeaders;
import com.atomjack.vcfp.PlexSubscription;
import com.atomjack.shared.Preferences;
import com.atomjack.vcfp.QueryString;
import com.atomjack.vcfp.R;
import com.atomjack.shared.UriDeserializer;
import com.atomjack.shared.UriSerializer;
import com.atomjack.vcfp.VoiceControlForPlexApplication;
import com.atomjack.vcfp.activities.CastActivity;
import com.atomjack.vcfp.activities.MainActivity;
import com.atomjack.vcfp.activities.NowPlayingActivity;
import com.atomjack.vcfp.activities.SubscriptionActivity;
import com.atomjack.vcfp.activities.VCFPActivity;
import com.atomjack.vcfp.interfaces.PlexPlayQueueHandler;
import com.atomjack.vcfp.model.Connection;
import com.atomjack.vcfp.model.MediaContainer;
import com.atomjack.vcfp.model.PlexClient;
import com.atomjack.vcfp.model.PlexDirectory;
import com.atomjack.vcfp.model.PlexMedia;
import com.atomjack.vcfp.model.PlexResponse;
import com.atomjack.vcfp.model.PlexServer;
import com.atomjack.vcfp.model.PlexTrack;
import com.atomjack.vcfp.model.PlexVideo;
import com.atomjack.vcfp.net.PlexHttpClient;
import com.atomjack.vcfp.net.PlexHttpMediaContainerHandler;
import com.atomjack.vcfp.net.PlexHttpResponseHandler;
import com.bugsense.trace.BugSenseHandler;
import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.DataMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlexSearchService extends Service {

	private String queryText;
	private SearchFeedback feedback;
	private Gson gsonRead = new GsonBuilder()
					.registerTypeAdapter(Uri.class, new UriDeserializer())
					.create();

	private Gson gsonWrite = new GsonBuilder()
					.registerTypeAdapter(Uri.class, new UriSerializer())
					.create();

	private ConcurrentHashMap<String, PlexServer> plexmediaServers = new ConcurrentHashMap<String, PlexServer>();
	private Map<String, PlexClient> clients;

	private PlexClient client = null;
	private PlexServer specifiedServer = null;
	private int serversSearched = 0;
	private List<PlexVideo> videos = new ArrayList<PlexVideo>();
	private Boolean videoPlayed = false;
	private List<PlexDirectory> shows = new ArrayList<PlexDirectory>();
	private Boolean resumePlayback = false;
	private List<PlexTrack> tracks = new ArrayList<PlexTrack>();
	private List<PlexDirectory> albums = new ArrayList<PlexDirectory>();

	private boolean didClientScan = false;

  private boolean shuffle = false;

	private ArrayList<String> queries;
	// Will be set to true after we scan for servers, so we don't have to do it again on the next query
	private boolean didServerScan = false;

  private PlexSubscription plexSubscription;

  private VCFPActivity.NetworkState currentNetworkState;

  private boolean fromWear = false;

	// Chromecast
	MediaRouter mMediaRouter;
	MediaRouterCallback mMediaRouterCallback;
	MediaRouteSelector mMediaRouteSelector;
	GoogleApiClient mApiClient;
	boolean mWaitingForReconnect = false;
	Cast.Listener mCastClientListener;
	ConnectionCallbacks mConnectionCallbacks;
  private CastPlayerManager castPlayerManager;

	// Callbacks for when we figure out what action the user wishes to take.
	private myRunnable actionToDo;
	private interface myRunnable {
		void run();
	}
	// An instance of this interface will be returned by handleVoiceSearch when no server discovery is needed (e.g. pause/resume/stop playback or offset)
	private interface StopRunnable extends myRunnable {}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Logger.d("PlexSearch: onStartCommand");

		if(BuildConfig.USE_BUGSENSE)
			BugSenseHandler.initAndStartSession(PlexSearchService.this, MainActivity.BUGSENSE_APIKEY);

		videoPlayed = false;
    shuffle = false;

    if(plexSubscription == null) {
      plexSubscription = VoiceControlForPlexApplication.getInstance().plexSubscription;
    }

    if(castPlayerManager == null)
      castPlayerManager = VoiceControlForPlexApplication.getInstance().castPlayerManager;
    castPlayerManager.setContext(this);
    if(castPlayerManager.isSubscribed()) {
      Logger.d("CAST MANAGER IS SUBSCRIBED");
    }

    if(intent.getBooleanExtra(WearConstants.FROM_WEAR, false) == true && VoiceControlForPlexApplication.getInstance().hasWear()) {
      fromWear = true;
    }

    currentNetworkState = VCFPActivity.NetworkState.getCurrentNetworkState(this);

		Logger.d("action: %s", intent.getAction());
		Logger.d("scan type: %s", intent.getStringExtra(com.atomjack.shared.Intent.SCAN_TYPE));
		if(intent.getAction() != null) {
      if (intent.getAction().equals(PlexScannerService.ACTION_SERVER_SCAN_FINISHED)) {
        // We just scanned for servers and are returning from that, so set the servers we found
        // and then figure out which mClient to play to
        Logger.d("Got back from scanning for servers.");
        videoPlayed = false;
        plexmediaServers = VoiceControlForPlexApplication.servers;
        didServerScan = true;
        setClient();
      } else if (intent.getAction().equals(PlexScannerService.ACTION_CLIENT_SCAN_FINISHED)) {
        // Got back from client scan, so set didClientScan to true so we don't do this again, and save the clients we got, then continue
        didClientScan = true;
        ArrayList<PlexClient> cs = intent.getParcelableArrayListExtra(com.atomjack.shared.Intent.EXTRA_CLIENTS);
        if (cs != null) {
          VoiceControlForPlexApplication.clients = new HashMap<String, PlexClient>();
          for (PlexClient c : cs) {
            VoiceControlForPlexApplication.clients.put(c.name, c);
          }
          clients = (HashMap) VoiceControlForPlexApplication.clients;
          clients.putAll(VoiceControlForPlexApplication.castClients);
        }
        startup();
      }
		} else {
			queryText = null;
			client = null;

			mMediaRouter = MediaRouter.getInstance(getApplicationContext());
			mMediaRouteSelector = new MediaRouteSelector.Builder()
							.addControlCategory(CastMediaControlIntent.categoryForCast(BuildConfig.CHROMECAST_APP_ID))
							.build();
			mMediaRouterCallback = new MediaRouterCallback();
			mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);

			mConnectionCallbacks = new ConnectionCallbacks();

			mCastClientListener = new Cast.Listener() {
				@Override
				public void onApplicationStatusChanged() {
					if (mApiClient != null) {
						Logger.d("onApplicationStatusChanged: "
										+ Cast.CastApi.getApplicationStatus(mApiClient));
					}
				}

				@Override
				public void onVolumeChanged() {
					if (mApiClient != null) {
						Logger.d("onVolumeChanged: " + Cast.CastApi.getVolume(mApiClient));
					}
				}

				@Override
				public void onApplicationDisconnected(int errorCode) {
					// TODO: Teardown?
					//teardown();
				}
			};

			queries = new ArrayList<String>();
			clients = (HashMap)VoiceControlForPlexApplication.clients;
      clients.putAll(VoiceControlForPlexApplication.castClients);
			resumePlayback = false;

			specifiedServer = gsonRead.fromJson(intent.getStringExtra(com.atomjack.shared.Intent.EXTRA_SERVER), PlexServer.class);
			if(specifiedServer != null)
				Logger.d("specified server %s", specifiedServer);
			PlexClient thisClient = gsonRead.fromJson(intent.getStringExtra(com.atomjack.shared.Intent.EXTRA_CLIENT), PlexClient.class);
			if(thisClient != null)
				client = thisClient;
			if(intent.getBooleanExtra(com.atomjack.shared.Intent.EXTRA_RESUME, false))
				resumePlayback = true;

			if (intent.getExtras().getStringArrayList(RecognizerIntent.EXTRA_RESULTS) != null) {
				Logger.d("internal query");
				// Received spoken query from the RecognizerIntent
				ArrayList<String> voiceResults = intent.getExtras().getStringArrayList(RecognizerIntent.EXTRA_RESULTS);
				for(String q : voiceResults) {
					if(q.matches(getString(R.string.pattern_recognition))) {
						if(!queries.contains(q.toLowerCase()))
							queries.add(q.toLowerCase());
					}
				}
				if(queries.size() == 0) {
          Logger.d("Didn't understand query %s", intent.getExtras().getStringArrayList(RecognizerIntent.EXTRA_RESULTS));
					feedback.e(getResources().getString(R.string.didnt_understand_that));
					return Service.START_NOT_STICKY;
				}
			} else {
				// Received spoken query from Google Search API
				Logger.d("Google Search API query");
				queries.add(intent.getStringExtra(com.atomjack.shared.Intent.EXTRA_QUERYTEXT));
			}

			if(client == null) {
        client = gsonRead.fromJson(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.CLIENT, ""), PlexClient.class);
        Logger.d("[PlexSearchService] set client to %s", client);
      }

			if(client == null && didClientScan) {
				// No mClient set in options, and either none specified in the query or I just couldn't find it.
				feedback.e(getResources().getString(R.string.client_not_specified));
				return Service.START_NOT_STICKY;
			}

//      if(client.isCastClient)
//        castPlayerManager.subscribe(client);
			if (queries.size() > 0) {
				Logger.d("Starting up, with queries: %s", queries);
				startup();
			} else
				feedback.e(getResources().getString(R.string.didnt_understand_that));
		}
		return Service.START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		feedback.destroy();
		super.onDestroy();
	}

	@Override
	public void onCreate() {
		Logger.d("PlexSearch onCreate");
		queryText = null;
		feedback = new SearchFeedback(this);
	}

	@Override
	public IBinder onBind(Intent intent) {
		Logger.d("PlexSearch: onBind");
		return null;
	}

	private void startup() {
		queryText = queries.remove(0);

		if(!didClientScan) {
			if(queryText.matches(getString(R.string.pattern_on_client))) {
				// A client was specified in the query, so let's scan for clients before proceeding.
				// First, insert the query text back into the queries array, so we can use it after the scan is done.
				queries.add(0, queryText);
        sendClientScanIntent();
				return;
			}
		}


		Logger.d("Starting up with query string: %s", queryText);
		tracks = new ArrayList<PlexTrack>();
		videos = new ArrayList<PlexVideo>();
		shows = new ArrayList<PlexDirectory>();

		final PlexServer defaultServer = gsonRead.fromJson(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.SERVER, ""), PlexServer.class);
		if(specifiedServer != null && client != null && !specifiedServer.name.equals(getResources().getString(R.string.scan_all))) {
			// got a specified server and mClient from a shortcut
			Logger.d("Got hardcoded server and mClient from shortcut");
			plexmediaServers = new ConcurrentHashMap<String, PlexServer>();
			plexmediaServers.put(specifiedServer.name, specifiedServer);
			setClient();
		} else if(specifiedServer == null && defaultServer != null && !defaultServer.name.equals(getResources().getString(R.string.scan_all))) {
			// Use the server specified in the main settings
			Logger.d("Using server and client specified in main settings");
			plexmediaServers = new ConcurrentHashMap<String, PlexServer>();
			plexmediaServers.put(defaultServer.name, defaultServer);
			setClient();
		} else {
			// Scan All was chosen
			Logger.d("Scan all was chosen");

			if(didServerScan) {
				setClient();
				return;
			}
			// First, see if what needs to be done actually needs to know about the server (i.e. pause/stop/resume playback of offset).
			// If it doesn't, execute the action and return as we don't need to do anything else. However, also check to see if the user
			// has specified a client (using " on <mClient name>") - if this is the case, we will need to find that mClient via server
			// discovery
			myRunnable actionToDo = handleVoiceSearch(true);
			if(actionToDo == null) {
				startup();
			} else {
				if (actionToDo instanceof StopRunnable && !queryText.matches(getString(R.string.pattern_on_client))) {
					actionToDo.run();
					return;
				}

        Intent scannerIntent = new Intent(PlexSearchService.this, PlexScannerService.class);
        scannerIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        scannerIntent.putExtra(PlexScannerService.CLASS, PlexSearchService.class);
        scannerIntent.setAction(PlexScannerService.ACTION_SCAN_SERVERS);
        startService(scannerIntent);
				feedback.m("Scanning for Plex Servers");
			}
		}
	}

	private void setClient() {
		actionToDo = handleVoiceSearch();
		if(actionToDo == null) {
			startup();
		} else
			actionToDo.run();
	}

	private myRunnable handleVoiceSearch() {
		return handleVoiceSearch(false);
	}

	private myRunnable handleVoiceSearch(boolean noChange) {
		Logger.d("GOT QUERY: %s", queryText);

//		resumePlayback = false;

		Pattern p;
		Matcher matcher;

		if(!noChange) {
			p = Pattern.compile(getString(R.string.pattern_on_client), Pattern.DOTALL);
			matcher = p.matcher(queryText);
      Pattern p2 = Pattern.compile(getString(R.string.pattern_on_shuffle), Pattern.DOTALL);
      Matcher matcher2 = p2.matcher(queryText);

			if (matcher.find() && !matcher2.find()) {
				String specifiedClient = matcher.group(2).toLowerCase();

				Logger.d("Clients: %d", clients.size());
				Logger.d("Specified client: %s", specifiedClient);
				//for (int i = 0; i < clients.size(); i++) {
				for(PlexClient c : clients.values()) {
					if (c.name.toLowerCase().equals(specifiedClient)) {
            // TODO: Finish
            if(c.isCastClient && !VoiceControlForPlexApplication.getInstance().hasChromecast()) {
              return new StopRunnable() {
                @Override
                public void run() {
                  feedback.e(R.string.must_purchase_chromecast_error);
                }
              };
            } else {
              client = c;
//              if(client.isCastClient)
//                castPlayerManager.subscribe(client);
              queryText = queryText.replaceAll(getString(R.string.pattern_on_client), "$1");
              Logger.d("query text now %s", queryText);
              break;
            }
					}
				}
			}

			// Check for a sentence starting with "resume watching"
			p = Pattern.compile(getString(R.string.pattern_resume_watching));
			matcher = p.matcher(queryText);
			if(matcher.find()) {
				resumePlayback = true;
				// Replace "resume watching" with just "watch" so the pattern matching below works
				queryText = matcher.replaceAll(getString(R.string.pattern_watch));
			}

      // Check for a sentence ending with "on shuffle"
      p = Pattern.compile(getString(R.string.pattern_on_shuffle));
      matcher = p.matcher(queryText);
      if(matcher.find()) {
        shuffle = true;
        // Remove "on shuffle" from the query text
        queryText = matcher.replaceAll("").trim();
        Logger.d("Shuffling, query is now !%s!", queryText);
      } else {
        Logger.d("No shuffle");
      }
		}

		// Done changing the query if the user said "resume watching" or specified a client

		p = Pattern.compile( getString(R.string.pattern_watch_movie), Pattern.DOTALL);
		matcher = p.matcher(queryText);
		if(matcher.find()) {
			final String queryTerm = matcher.group(2);
			return new myRunnable() {
				@Override
				public void run() {
					doMovieSearch(queryTerm);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_watch_season_episode_of_show));
		matcher = p.matcher(queryText);

		if(matcher.find()) {
			final String queryTerm = matcher.group(4);
			final String season = matcher.group(2);
			final String episode = matcher.group(3);
			return new myRunnable() {
				@Override
				public void run() {
					doShowSearch(queryTerm, season, episode);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_watch_show_season_episode));
		matcher = p.matcher(queryText);

		if(matcher.find()) {
			final String queryTerm = matcher.group(2);
			final String season = matcher.group(3);
			final String episode = matcher.group(4);
			return new myRunnable() {
				@Override
				public void run() {
					doShowSearch(queryTerm, season, episode);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_watch_episode_of_show));
		matcher = p.matcher(queryText);
		if(matcher.find()) {
			final String episodeSpecified = matcher.group(2);
			final String showSpecified = matcher.group(3);
			return new myRunnable() {
				@Override
				public void run() {
					doShowSearch(episodeSpecified, showSpecified);
				}
			};
		}


		p = Pattern.compile(getString(R.string.pattern_watch_next_episode_of_show));
		matcher = p.matcher(queryText);

		if(matcher.find()) {
			final String queryTerm = matcher.group(2);
			return new myRunnable() {
				@Override
				public void run() {
					doNextEpisodeSearch(queryTerm, false);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_watch_latest_episode_of_show));
		matcher = p.matcher(queryText);

		if(matcher.find()) {
			final String queryTerm = matcher.group(3);
			Logger.d("found latest: %s", queryTerm);
			return new myRunnable() {
				@Override
				public void run() {
					doLatestEpisodeSearch(queryTerm);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_watch_show_episode_named));
		matcher = p.matcher(queryText);
		if(matcher.find()) {
			final String episodeSpecified = matcher.group(3);
			final String showSpecified = matcher.group(2);
			return new myRunnable() {
				@Override
				public void run() {
						doShowSearch(episodeSpecified, showSpecified);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_watch2));
		matcher = p.matcher(queryText);

		if(matcher.find()) {
			final String queryTerm = matcher.group(2);
			return new myRunnable() {
				@Override
				public void run() {
					doMovieSearch(queryTerm);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_listen_to_album_by_artist));
		matcher = p.matcher(queryText);
		if(matcher.find()) {
			final String album = matcher.group(1);
			final String artist = matcher.group(2);
			return new myRunnable() {
				@Override
				public void run() {
					searchForAlbum(artist, album);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_listen_to_album));
		matcher = p.matcher(queryText);
		if(matcher.find()) {
			final String album = matcher.group(1);
			return new myRunnable() {
				@Override
				public void run() {
					searchForAlbum("", album);
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_listen_to_song_by_artist));
		matcher = p.matcher(queryText);
		if(matcher.find()) {
			final String track = matcher.group(1);
			final String artist = matcher.group(2);
			return new myRunnable() {
				@Override
				public void run() {
					searchForSong(artist, track);
				}
			};
		}

    p = Pattern.compile(getString(R.string.pattern_listen_to_artist));
    matcher = p.matcher(queryText);
    if(matcher.find()) {
      final String artist = matcher.group(1);
      shuffle = true; // when specifying just an artist, shuffle all that artist's songs
      return new myRunnable() {
        @Override
        public void run() {
          searchForArtist(artist);
        }
      };
    }

		p = Pattern.compile(getString(R.string.pattern_pause_playback), Pattern.DOTALL);
		matcher = p.matcher(queryText);
		if (matcher.find()) {
			return new StopRunnable() {
				@Override
				public void run() {
					pausePlayback();
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_resume_playback), Pattern.DOTALL);
		matcher = p.matcher(queryText);
		if (matcher.find()) {
			Logger.d("resuming playback");
			return new StopRunnable() {
				@Override
				public void run() {
					resumePlayback();
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_stop_playback), Pattern.DOTALL);
		matcher = p.matcher(queryText);
		if (matcher.find()) {
			Logger.d("stopping playback");
			return new StopRunnable() {
				@Override
				public void run() {
					stopPlayback();
				}
			};
		}

		p = Pattern.compile(getString(R.string.pattern_offset), Pattern.DOTALL);
		matcher = p.matcher(queryText);
		if (matcher.find()) {
			String groupOne = matcher.group(2) != null && matcher.group(2).matches("two|to") ? "2" : matcher.group(2);
			String groupThree = matcher.group(4) != null && matcher.group(4).matches("two|to") ? "2" : matcher.group(4);
			String groupFive = matcher.group(6) != null && matcher.group(6).matches("two|to") ? "2" : matcher.group(6);
			int hours = 0, minutes = 0, seconds = 0;
			if(matcher.group(5) != null && matcher.group(5).matches(getString(R.string.pattern_minutes)))
				minutes = Integer.parseInt(groupThree);
			else if(matcher.group(3) != null && matcher.group(3).matches(getString(R.string.pattern_minutes)))
				minutes = Integer.parseInt(groupOne);

			if(matcher.group(7) != null && matcher.group(7).matches(getString(R.string.pattern_seconds)))
				seconds = Integer.parseInt(groupFive);
			else if(matcher.group(5) != null && matcher.group(5).matches(getString(R.string.pattern_seconds)))
				seconds = Integer.parseInt(groupThree);
			else if(matcher.group(3).matches(getString(R.string.pattern_seconds)))
				seconds = Integer.parseInt(groupOne);

			if(matcher.group(3).matches(getString(R.string.pattern_hours)))
				hours = Integer.parseInt(groupOne);
			final int h = hours;
			final int m = minutes;
			final int s = seconds;
			return new StopRunnable() {
				@Override
				public void run() {
					seekTo(h, m, s);
				}
			};
		}

    p = Pattern.compile(getString(R.string.pattern_forward), Pattern.DOTALL);
    matcher = p.matcher(queryText);
    if(matcher.find()) {
      final String amount = matcher.group(1) != null && matcher.group(1).matches("two|to") ? "2" : matcher.group(1);
      final String del = matcher.group(2);
      Logger.d("[ffr] del = %s", del);
      int mul = 1; // default multiplier, for seconds
      if(del.matches("minutes?"))
        mul = 60;
      else if(del.matches("hours?"))
        mul = 60*60;
      Logger.d("[ffr] mul = %d", mul);
      final int seconds = Integer.parseInt(amount) * mul;
      return new StopRunnable() {
        @Override
        public void run() {
          Logger.d("[ffr] Skipping ahead %d seconds", seconds);
          int currentOffset = 0;
          if(client.isCastClient) {
            currentOffset = Integer.parseInt(castPlayerManager.getNowPlayingMedia().viewOffset);
          } else {
            NowPlayingActivity listener = (NowPlayingActivity)plexSubscription.getListener();
            if (listener != null) {
              currentOffset = Integer.parseInt(listener.getNowPlayingMedia().viewOffset);
            }
          }
          Logger.d("[ffr] currentOffset: %d", currentOffset);
          seekTo((currentOffset + seconds) * 1000);
        }
      };
    }

    p = Pattern.compile(getString(R.string.pattern_rewind), Pattern.DOTALL);
    matcher = p.matcher(queryText);
    if(matcher.find()) {
      final String amount = matcher.group(2) != null && matcher.group(2).matches("two|to") ? "2" : matcher.group(2);
      final String del = matcher.group(3);
      Logger.d("[ffr] del = %s", del);
      int mul = 1; // default multiplier, for seconds
      if(del.matches("minutes?"))
        mul = 60;
      else if(del.matches("hours?"))
        mul = 60*60;
      final int seconds = Integer.parseInt(amount) * mul;
      return new StopRunnable() {
        @Override
        public void run() {
          Logger.d("[ffr] Rewinding %d seconds", seconds);
          int currentOffset = 0;
          if(client.isCastClient) {
            currentOffset = Integer.parseInt(castPlayerManager.getNowPlayingMedia().viewOffset);
          } else {
            NowPlayingActivity listener = (NowPlayingActivity)plexSubscription.getListener();
            if (listener != null) {
              currentOffset = Integer.parseInt(listener.getNowPlayingMedia().viewOffset);
            }
          }
          Logger.d("[ffr] currentOffset: %d", currentOffset);
          seekTo((currentOffset - seconds) * 1000);
        }
      };
    }

    p = Pattern.compile(getString(R.string.pattern_connect_to), Pattern.DOTALL);
    matcher = p.matcher(queryText);
    if(matcher.find()) {
      final String connectToClient = matcher.group(1);
      PlexClient foundClient = null;
      for(PlexClient theClient : VoiceControlForPlexApplication.clients.values()) {
        if(compareTitle(theClient.name, connectToClient)) {
          foundClient = theClient;
          break;
        }
      }
      final PlexClient theClient = foundClient;
      if(foundClient == null) {
        if(didClientScan) {
          return new StopRunnable() {
            @Override
            public void run() {
              feedback.e(R.string.client_not_found);
            }
          };
        } else {
          return new StopRunnable() {
            @Override
            public void run() {
              queries.add(0, queryText);
              sendClientScanIntent();
            }
          };
        }
      } else {
        return new StopRunnable() {
          @Override
          public void run() {
            Logger.d("PlexSearchService Subscribing to %s", theClient.name);
            if(VoiceControlForPlexApplication.getInstance().plexSubscription.getListener() != null)
              VoiceControlForPlexApplication.getInstance().plexSubscription.subscribe(theClient);
            else {
              Intent sendIntent = new Intent(PlexSearchService.this, SubscriptionActivity.class);
              sendIntent.setAction(SubscriptionActivity.ACTION_SUBSCRIBE);
              sendIntent.putExtra(SubscriptionActivity.CLIENT, theClient);
              sendIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
              sendIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
              sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              startActivity(sendIntent);
            }
          }
        };
      }
    }

    p = Pattern.compile(getString(R.string.pattern_disconnect), Pattern.DOTALL);
    matcher = p.matcher(queryText);
    if(matcher.find()) {
      return new StopRunnable() {
        @Override
        public void run() {
          if(VoiceControlForPlexApplication.getInstance().plexSubscription.getListener() != null)
            VoiceControlForPlexApplication.getInstance().plexSubscription.unsubscribe();
          else {
            Intent sendIntent = new Intent(PlexSearchService.this, SubscriptionActivity.class);
            sendIntent.setAction(SubscriptionActivity.ACTION_UNSUBSCRIBE);
            sendIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            sendIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(sendIntent);
          }
        }
      };
    }







    if(queries.size() > 0)
			return null;
		else {
			return new myRunnable() {
				@Override
				public void run() {
					feedback.e(getString(R.string.didnt_understand), queryText);
				}
			};
		}
	}

	private void adjustPlayback(String which, final String onFinish) {
		ArrayList<String> validModes = new ArrayList<String>(Arrays.asList("pause", "play", "stop"));
		if(validModes.indexOf(which) == -1)
			return;

    if(client.isCastClient) {
      if(which.equals("pause"))
        castPlayerManager.pause();
      else if(which.equals("play"))
        castPlayerManager.play();
      else if(which.equals("stop"))
        castPlayerManager.stop();
      return;
    }

		PlexHttpResponseHandler responseHandler = new PlexHttpResponseHandler()
		{
			@Override
			public void onSuccess(PlexResponse r)
			{
				Boolean passed = true;
				if(r.code != null) {
					if(!r.code.equals("200")) {
						passed = false;
					}
				}
				Logger.d("Playback response: %s", r.code);
				if(passed) {
					feedback.m(onFinish);
				} else {
					feedback.e(getResources().getString(R.string.http_status_code_error), r.code);
				}
			}

			@Override
			public void onFailure(Throwable error) {
				feedback.e(getResources().getString(R.string.got_error), error.getMessage());
			}
		};
		if(which.equals("pause"))
			client.pause(responseHandler);
		else if(which.equals("play"))
			client.play(responseHandler);
		else if(which.equals("stop"))
			client.stop(responseHandler);
	}

	private void pausePlayback() {
		adjustPlayback("pause", getResources().getString(R.string.playback_paused));
	}

	private void resumePlayback() {
		adjustPlayback("play", getResources().getString(R.string.playback_resumed));
	}

	private void stopPlayback() {
		adjustPlayback("stop", getResources().getString(R.string.playback_stopped));
		if(VoiceControlForPlexApplication.isApplicationVisible()) {
			Intent stopIntent = new Intent(this, NowPlayingActivity.class);
			stopIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			stopIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
			stopIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			stopIntent.putExtra("finish", true);
			startActivity(stopIntent);
		}
	}

	private void seekTo(int hours, int minutes, int seconds) {
		Logger.d("Seeking to %d hours, %d minutes, %d seconds", hours, minutes, seconds);
		int offset = 1000*((hours*60*60)+(minutes*60)+seconds);
		Logger.d("offset: %d milliseconds", offset);

    seekTo(offset);
	}

  private void seekTo(int offset) {
    if(client.isCastClient) {
      castPlayerManager.seekTo(offset / 1000);
    } else {
      client.seekTo(offset, new PlexHttpResponseHandler() {
        @Override
        public void onSuccess(PlexResponse r) {
          Boolean passed = true;
          if (r.code != null) {
            if (!r.code.equals("200")) {
              passed = false;
            }
          }
          Logger.d("Playback response: %s", r.code);
          if (!passed) {
            feedback.e(getResources().getString(R.string.http_status_code_error), r.code);
          }
        }

        @Override
        public void onFailure(Throwable error) {
          feedback.e(getResources().getString(R.string.got_error), error.getMessage());
        }
      });
    }
  }

	private void doMovieSearch(final String queryTerm) {
		Logger.d("Doing movie search. %d servers", plexmediaServers.size());
		feedback.m(getString(R.string.searching_for), queryTerm);
		serversSearched = 0;
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.movieSectionsSearched = 0;
					Logger.d("!Searching server (for movies): %s, %d sections", server.name, server.movieSections.size());
//          Logger.d("Server active connection: %s", server.activeConnection);

					if(server.movieSections.size() == 0) {
						serversSearched++;
						if(serversSearched == plexmediaServers.size()) {
							onMovieSearchFinished(queryTerm);
						}
					}
					for(int i=0;i<server.movieSections.size();i++) {
						String section = server.movieSections.get(i);
						String path = String.format("/library/sections/%s/search?type=1&query=%s", section, queryTerm);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler()
						{
							@Override
							public void onSuccess(MediaContainer mc)
							{
								server.movieSectionsSearched++;
								for(int j=0;j<mc.videos.size();j++) {
									PlexVideo video = mc.videos.get(j);
									if(compareTitle(video.title.toLowerCase(), queryTerm.toLowerCase())) {
										video.server = server;
										video.showTitle = mc.grandparentTitle;
                    video.parentArt = mc.art;
										videos.add(video);
									}
								}
								Logger.d("Videos: %d", mc.videos.size());
								Logger.d("%d sections searched out of %d", server.movieSectionsSearched, server.movieSections.size());
								if(server.movieSections.size() == server.movieSectionsSearched) {
									serversSearched++;
									if(serversSearched == plexmediaServers.size()) {
										onMovieSearchFinished(queryTerm);
									}
								}
							}

							@Override
							public void onFailure(Throwable error) {
								error.printStackTrace();
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if(serversSearched == plexmediaServers.size()) {
						onMovieSearchFinished(queryTerm);
					}
				}
			});
		}
	}

	private static Boolean compareTitle(String title, String queryTerm) {
		// First, check if the two terms are equal
		if(title.toLowerCase().equals(queryTerm.toLowerCase()))
			return true;

		// No equal match, so split the query term up by words, and see if the title contains every single word
		String[] words = queryTerm.split(" ");
		Boolean missing = false;
		for(int i=0;i<words.length;i++) {
			if(!title.toLowerCase().matches(".*\\b" + words[i].toLowerCase() + "\\b.*"))
				missing = true;
		}
		return !missing;
	}

	private void onMovieSearchFinished(String queryTerm) {
		Logger.d("Done searching! Have videos: %d", videos.size());

		if(videos.size() == 1) {
			Logger.d("Chosen video: %s", videos.get(0).title);
      fetchAndPlayMedia(videos.get(0));
		} else if(videos.size() > 1) {
			// We found more than one match, but let's see if any of them are an exact match
			Boolean exactMatch = false;
			for(int i=0;i<videos.size();i++) {
				Logger.d("Looking at video %s", videos.get(i).title);
				if(videos.get(i).title.toLowerCase().equals(queryTerm.toLowerCase())) {
					Logger.d("found exact match!");
					exactMatch = true;
          fetchAndPlayMedia(videos.get(i));
					break;
				}
			}
			if(!exactMatch) {
				if(queries.size() > 0)
					startup();
				else
					feedback.e(getResources().getString(R.string.found_more_than_one_movie));
				return;
			}
		} else {
			Logger.d("Didn't find a video");
			// Let's also support using this syntax to play the next episode in a tv show. Probably will want to use a different error message if nothing is found, though.
			doNextEpisodeSearch(queryTerm, true);
		}
	}

  private void requestTransientAccessToken(PlexServer server, final AfterTransientTokenRequest onFinish) {
    String path = "/security/token?type=delegation&scope=all";
    PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler() {
      @Override
      public void onSuccess(MediaContainer mediaContainer) {
        onFinish.success(mediaContainer.token);
      }

      @Override
      public void onFailure(Throwable error) {
        onFinish.failure();
      }
    });
  }

  // When searching for media, the media element contained inside the media container doesn't necessarily have a complete set of attributes. So,
  // fetch the specific media element by its key. This prevents the need to add new missing fields to the media
  //
  private void fetchAndPlayMedia(final PlexMedia media) {
    PlexHttpClient.get(media.server, media.key, new PlexHttpMediaContainerHandler() {
      @Override
      public void onSuccess(MediaContainer mediaContainer) {
        PlexMedia theMedia = null;
        if(media instanceof PlexVideo)
          theMedia = mediaContainer.videos.get(0);
        else if(media instanceof PlexTrack)
          theMedia = mediaContainer.tracks.get(0);
        if(theMedia != null) {
          theMedia.server = media.server;
          playMedia(theMedia);
          onActionFinished(WearConstants.SPEECH_QUERY_RESULT, false, theMedia);
        } else {
          // TODO: Handle failure
        }
      }

      @Override
      public void onFailure(Throwable error) {
        // TODO: Handle failure
      }
    });
  }

	private void playMedia(final PlexMedia media) {
    playMedia(media, null);
  }

  private void playMedia(final PlexMedia media, final PlexDirectory album) {
		if(media.server.owned)
			playMedia(media, album, null);
		else {
			// TODO: switch this to the PlexServer method and verify
			requestTransientAccessToken(media.server, new AfterTransientTokenRequest() {
				@Override
				public void success(String token) {
					playMedia(media, album, token);
				}

				@Override
				public void failure() {
					// Just try to play without a transient token
					playMedia(media, album, null);
				}
			});
		}
	}

	private void playMedia(final PlexMedia media, final PlexDirectory album, final String transientToken) {
		Logger.d("Playing media: %s", media.title);
		Logger.d("Client: %s", client);

    Logger.d("currentNetworkState: %s", currentNetworkState);
    if(currentNetworkState == VCFPActivity.NetworkState.MOBILE) {
      media.server.localPlay(media, resumePlayback, transientToken);
    } else if(currentNetworkState == VCFPActivity.NetworkState.WIFI) {

      media.server.findServerConnection(new ActiveConnectionHandler() {
        @Override
        public void onSuccess(final Connection connection) {
          try {
            PlexHttpClient.createPlayQueue(connection, media, transientToken, new PlexPlayQueueHandler() {
              @Override
              public void onSuccess(MediaContainer mediaContainer) {
                if(media instanceof PlexTrack) {

                } else {
                  // Insert the server into each video in the playlist
                  for (int i = 0; i < mediaContainer.videos.size(); i++) {
                    mediaContainer.videos.get(i).server = media.server;
                    if (mediaContainer.videos.get(i).isClip())
                      mediaContainer.videos.get(i).setClipDuration();
                  }
                }
                QueryString qs = new QueryString("machineIdentifier", media.server.machineIdentifier);
                qs.add("key", media.key);
                qs.add("containerKey", String.format("/playQueues/%s", mediaContainer.playQueueID));
                qs.add("port", connection.port);
                qs.add("address", connection.address);

                if (resumePlayback && media.viewOffset != null)
                  qs.add("viewOffset", media.viewOffset);
                if (transientToken != null)
                  qs.add("token", transientToken);
                if (media.server.accessToken != null)
                  qs.add(PlexHeaders.XPlexToken, media.server.accessToken);

                if (album != null)
                  qs.add("containerKey", album.key);

                if(client.isCastClient) {
                  Logger.d("playQueueID: %s", mediaContainer.playQueueID);
                  Logger.d("num videos/tracks: %d", media instanceof PlexTrack ? mediaContainer.tracks.size() : mediaContainer.videos.size());
                  Intent sendIntent = new Intent(PlexSearchService.this, CastActivity.class);
                  sendIntent.setAction(com.atomjack.shared.Intent.CAST_MEDIA);
                  sendIntent.putExtra(WearConstants.FROM_WEAR, fromWear);
                  sendIntent.putExtra(com.atomjack.shared.Intent.EXTRA_MEDIA, mediaContainer.videos.get(0));
                  sendIntent.putParcelableArrayListExtra(com.atomjack.shared.Intent.EXTRA_ALBUM, media instanceof PlexTrack ? (ArrayList)mediaContainer.tracks : (ArrayList)mediaContainer.videos);
                  sendIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLIENT, client);
                  sendIntent.putExtra("resume", resumePlayback);
                  sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                  startActivity(sendIntent);
                } else {
                  String url = String.format("http://%s:%s/player/playback/playMedia?%s", client.address, client.port, qs);
                  PlexHttpClient.get(url, new PlexHttpResponseHandler() {
                    @Override
                    public void onSuccess(PlexResponse r) {
                      // If the host we're playing on is this device, we don't wanna do anything else here.
                      if (Utils.getIPAddress(true).equals(client.address) || r == null)
                        return;
                      if(media instanceof PlexTrack)
                        feedback.m(getResources().getString(R.string.now_listening_to), media.title, ((PlexTrack)media).artist, client.name);
                      else
                        feedback.m(getResources().getString(R.string.now_watching_video), media.isMovie() ? media.title : media.grandparentTitle, client.name);
                      Boolean passed = true;
                      if (r.code != null) {
                        if (!r.code.equals("200")) {
                          passed = false;
                        }
                      }
                      Logger.d("Playback response: %s", r.code);
                      if (passed) {
                        videoPlayed = true;
                        showPlayingMedia(media);
                      } else {
                        feedback.e(getResources().getString(R.string.http_status_code_error), r.code);
                      }
                    }

                    @Override
                    public void onFailure(Throwable error) {
                      feedback.e(getResources().getString(R.string.got_error), error.getMessage());
                    }
                  });
                }
              }
            });
          } catch (Exception e) {
            feedback.e(getResources().getString(R.string.got_error), e.getMessage());
            Logger.e("Exception trying to play video: %s", e.toString());
            e.printStackTrace();
          }
        }

        @Override
        public void onFailure(int statusCode) {
          // TODO: Handle no connection?
        }
      });
    }
	}

  private void castAlbum(List<PlexTrack> tracks) {
    Intent sendIntent = new Intent(this, CastActivity.class);
    sendIntent.setAction(com.atomjack.shared.Intent.CAST_MEDIA);
    sendIntent.putExtra(com.atomjack.shared.Intent.EXTRA_MEDIA, tracks.get(0));
    sendIntent.putParcelableArrayListExtra(com.atomjack.shared.Intent.EXTRA_ALBUM, (ArrayList<PlexTrack>)tracks);
    sendIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLIENT, client);
    sendIntent.putExtra("resume", resumePlayback);
    sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    sendIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    startActivity(sendIntent);
  }

	private void showPlayingMedia(PlexMedia media) {
    Logger.d("[PlexSearchService] nowPlayingMedia: %s", media.title);
		Intent nowPlayingIntent = new Intent(this, NowPlayingActivity.class);
    nowPlayingIntent.putExtra(WearConstants.FROM_WEAR, fromWear);
		nowPlayingIntent.putExtra(com.atomjack.shared.Intent.EXTRA_MEDIA, media);
		nowPlayingIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLIENT, client);
		nowPlayingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(nowPlayingIntent);
	}

	private void doNextEpisodeSearch(final String queryTerm, final boolean fallback) {
		feedback.m(getString(R.string.searching_for), queryTerm);
		serversSearched = 0;
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.showSectionsSearched = 0;
					if (server.tvSections.size() == 0) {
						serversSearched++;
						if (serversSearched == plexmediaServers.size()) {
							onFinishedNextEpisodeSearch(queryTerm, fallback);
						}
					}
					for (int i = 0; i < server.tvSections.size(); i++) {
						String section = server.tvSections.get(i);
						String path = String.format("/library/sections/%s/onDeck", section);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler() {
							@Override
							public void onSuccess(MediaContainer mc) {
								server.showSectionsSearched++;
								for (int j = 0; j < mc.videos.size(); j++) {
									PlexVideo video = mc.videos.get(j);
									if (compareTitle(video.grandparentTitle, queryTerm)) {
										video.server = server;
										video.thumb = video.grandparentThumb;
										video.showTitle = video.grandparentTitle;
                    video.parentArt = mc.art;
										videos.add(video);
										Logger.d("ADDING " + video.grandparentTitle);
									}
								}

								if (server.tvSections.size() == server.showSectionsSearched) {
									serversSearched++;
									if (serversSearched == plexmediaServers.size()) {
										onFinishedNextEpisodeSearch(queryTerm, fallback);
									}
								}
							}

							@Override
							public void onFailure(Throwable error) {
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if (serversSearched == plexmediaServers.size()) {
						onFinishedNextEpisodeSearch(queryTerm, fallback);
					}
				}
			});
		}
	}

	private void onFinishedNextEpisodeSearch(String queryTerm, boolean fallback) {
		if(videos.size() == 0) {
			if(queries.size() == 0)
				feedback.e(getResources().getString(fallback ? R.string.couldnt_find : R.string.couldnt_find_next), queryTerm);
			else {
				startup();
			}
		} else {
			if(videos.size() == 1)
				fetchAndPlayMedia(videos.get(0));
			else {
				// We found more than one matching show. Let's check if the title of any of the matching shows
				// exactly equals the query term, otherwise tell the user to be more specific.
				//
				int exactMatch = -1;
				for(int i=0;i<videos.size();i++) {
					if(videos.get(i).grandparentTitle.toLowerCase().equals(queryTerm.toLowerCase())) {
						exactMatch = i;
						break;
					}
				}

				if(exactMatch > -1) {
          fetchAndPlayMedia(videos.get(exactMatch));
				} else {
					feedback.e(getResources().getString(R.string.found_more_than_one_show));
					return;
				}
			}
		}
	}

	private void doLatestEpisodeSearch(final String queryTerm) {
		feedback.m(getString(R.string.searching_for), queryTerm);
		Logger.d("doLatestEpisodeSearch: %s", queryTerm);
		serversSearched = 0;
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.showSectionsSearched = 0;
					Logger.d("Searching server %s", server.name);
					if (server.tvSections.size() == 0) {
						Logger.d(server.name + " has no tv sections");
						serversSearched++;
						if (serversSearched == plexmediaServers.size()) {
							doLatestEpisode(queryTerm);
						}
					}
					for (int i = 0; i < server.tvSections.size(); i++) {
						String section = server.tvSections.get(i);
						String path = String.format("/library/sections/%s/search?type=2&query=%s", section, queryTerm);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler() {
							@Override
							public void onSuccess(MediaContainer mc) {
								server.showSectionsSearched++;
								for (int j = 0; j < mc.directories.size(); j++) {
									PlexDirectory show = mc.directories.get(j);
									if (compareTitle(show.title, queryTerm)) {
										show.server = server;
										shows.add(show);
										Logger.d("Adding %s", show.title);
									}
								}

								if (server.tvSections.size() == server.showSectionsSearched) {
									serversSearched++;
									if (serversSearched == plexmediaServers.size()) {
										doLatestEpisode(queryTerm);
									}
								}
							}

							@Override
							public void onFailure(Throwable error) {
								error.printStackTrace();
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if (serversSearched == plexmediaServers.size()) {
						doLatestEpisode(queryTerm);
					}
				}
			});
		}
	}

	private void doLatestEpisode(final String queryTerm) {
		if(shows.size() == 0) {
			if(queries.size() > 0)
				startup();
			else
				feedback.e(getResources().getString(R.string.couldnt_find), queryTerm);
			return;
		}
		PlexDirectory chosenShow = null;
		if(shows.size() > 1) {
			for(int i=0;i<shows.size();i++) {
				PlexDirectory show = shows.get(i);
				if(show.title.toLowerCase().equals(queryTerm.toLowerCase())) {
					chosenShow = show;
					break;
				}
			}
		} else {
			chosenShow = shows.get(0);
		}

		if(chosenShow == null) {
			if(queries.size() > 0)
				startup();
			else
				feedback.e(getResources().getString(R.string.found_more_than_one_show));
			return;
		}
		final PlexDirectory show = chosenShow;
		String path = String.format("/library/metadata/%s/allLeaves", show.ratingKey);
		PlexHttpClient.get(show.server, path, new PlexHttpMediaContainerHandler()
		{
			@Override
			public void onSuccess(MediaContainer mc)
			{
				PlexVideo latestVideo = null;
				for(int j=0;j<mc.videos.size();j++) {
					PlexVideo video = mc.videos.get(j);
					if(latestVideo == null || latestVideo.airDate().before(video.airDate())) {
//						video.showTitle = video.grandparentTitle;
//            video.parentArt = mc.art;
//            video.grandparentThumb = mc.art.replaceAll("\\/art\\/", "\\/thumb\\/");
						latestVideo = video;
					}
				}
				latestVideo.server = show.server;
				Logger.d("Found video: %s", latestVideo.airDate());
				if(latestVideo != null) {
					fetchAndPlayMedia(latestVideo);
				} else {
					if(queries.size() > 0)
						startup();
					else
						feedback.e(getResources().getString(R.string.couldnt_find), queryTerm);
					return;
				}
			}

			@Override
			public void onFailure(Throwable error) {
				feedback.e(getResources().getString(R.string.got_error), error.getMessage());
			}
		});
	}

	private void doShowSearch(final String episodeSpecified, final String showSpecified) {
		feedback.m(getString(R.string.searching_for_episode), showSpecified, episodeSpecified);
		serversSearched = 0;
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.showSectionsSearched = 0;
					if (server.tvSections.size() == 0) {
						serversSearched++;
						if (serversSearched == plexmediaServers.size()) {
							playSpecificEpisode(showSpecified);
						}
					}
					for (int i = 0; i < server.tvSections.size(); i++) {
						String section = server.tvSections.get(i);
						String path = String.format("/library/sections/%s/search?type=4&query=%s", section, episodeSpecified);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler() {
							@Override
							public void onSuccess(MediaContainer mc) {
								server.showSectionsSearched++;
								for (int j = 0; j < mc.videos.size(); j++) {
									Logger.d("Show: %s", mc.videos.get(j).grandparentTitle);
									PlexVideo video = mc.videos.get(j);
									if (compareTitle(video.grandparentTitle, showSpecified)) {
										video.server = server;
										video.thumb = video.grandparentThumb;
										video.showTitle = video.grandparentTitle;
                    video.parentArt = mc.art;
										Logger.d("Adding %s - %s.", video.showTitle, video.title);
										videos.add(video);
									}
								}

								if (server.tvSections.size() == server.showSectionsSearched) {
									serversSearched++;
									if (serversSearched == plexmediaServers.size()) {
										playSpecificEpisode(showSpecified);
									}
								}
							}

							@Override
							public void onFailure(Throwable error) {
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if (serversSearched == plexmediaServers.size()) {
						playSpecificEpisode(showSpecified);
					}
				}
			});



		}
	}

	private void playSpecificEpisode(String showSpecified) {
		if(videos.size() == 0) {
			if(queries.size() > 0)
				startup();
			else
				feedback.e(getResources().getString(R.string.couldnt_find_episode));
		} else if(videos.size() == 1) {
      fetchAndPlayMedia(videos.get(0));
		} else {
			Boolean exactMatch = false;
			for(int i=0;i<videos.size();i++) {
				if(videos.get(i).grandparentTitle.toLowerCase().equals(showSpecified.toLowerCase())) {
					exactMatch = true;
          fetchAndPlayMedia(videos.get(i));
					break;
				}
			}
			if(!exactMatch) {
				if(queries.size() > 0)
					startup();
				else
					feedback.e(getResources().getString(R.string.found_more_than_one_show));
			}
		}
	}

	private void doShowSearch(final String queryTerm, final String season, final String episode) {
		feedback.m(getString(R.string.searching_for_show_season_episode), queryTerm, season, episode);
		Logger.d("doShowSearch: %s s%s e%s", queryTerm, season, episode);
		serversSearched = 0;
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.showSectionsSearched = 0;
					Logger.d("Searching server %s", server.name);
					if (server.tvSections.size() == 0) {
						Logger.d("%s has no tv sections", server.name);
						serversSearched++;
						if (serversSearched == plexmediaServers.size()) {
							doEpisodeSearch(queryTerm, season, episode);
						}
					}
					for (int i = 0; i < server.tvSections.size(); i++) {
						String section = server.tvSections.get(i);
						String path = String.format("/library/sections/%s/search?type=2&query=%s", section, queryTerm);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler() {
							@Override
							public void onSuccess(MediaContainer mc) {
								server.showSectionsSearched++;
								for (int j = 0; j < mc.directories.size(); j++) {
									shows.add(mc.directories.get(j));
								}

								if (server.tvSections.size() == server.showSectionsSearched) {
									serversSearched++;
									if (serversSearched == plexmediaServers.size()) {
										doEpisodeSearch(queryTerm, season, episode);
									}
								}
							}

							@Override
							public void onFailure(Throwable error) {
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if (serversSearched == plexmediaServers.size()) {
						doEpisodeSearch(queryTerm, season, episode);
					}
				}
			});
		}
	}

	private void doEpisodeSearch(String queryTerm, final String season, final String episode) {
		Logger.d("Found shows: %d", shows.size());
		serversSearched = 0;
		for(final PlexServer server : plexmediaServers.values()) {
			if(shows.size() == 0 && serversSearched == plexmediaServers.size()) {
				serversSearched++;
				if(queries.size() == 0)
					feedback.e(getResources().getString(R.string.couldnt_find), queryTerm);
				else
					startup();
			} else if(shows.size() == 1) {
				final PlexDirectory show = shows.get(0);
				Logger.d("Show key: %s", show.key);
				PlexHttpClient.get(server, show.key, new PlexHttpMediaContainerHandler()
				{
					@Override
					public void onSuccess(MediaContainer mc)
					{
						PlexDirectory foundSeason = null;
						for(int i=0;i<mc.directories.size();i++) {
							PlexDirectory directory = mc.directories.get(i);
							if(directory.title.equals("Season " + season)) {
								Logger.d("Found season %s: %s.", season, directory.key);
								foundSeason = directory;
								break;
							}
						}

						if(foundSeason == null && serversSearched == plexmediaServers.size() && !videoPlayed) {
							serversSearched++;
							if(queries.size() == 0)
								feedback.e(getResources().getString(R.string.couldnt_find_season));
							else
								startup();
						} else if(foundSeason != null) {
							PlexHttpClient.get(server, foundSeason.key, new PlexHttpMediaContainerHandler()
							{
								@Override
								public void onSuccess(MediaContainer mc)
								{
									Boolean foundEpisode = false;
									Logger.d("Looking for episode %s", episode);
									Logger.d("videoPlayed: %s", videoPlayed);
									for(int i=0;i<mc.videos.size();i++) {
										Logger.d("Looking at episode %s", mc.videos.get(i).index);
										if(mc.videos.get(i).index.equals(episode) && !videoPlayed) {
											serversSearched++;
											PlexVideo video = mc.videos.get(i);
											video.server = server;
                      fetchAndPlayMedia(video);
											foundEpisode = true;
											break;
										}
									}
									Logger.d("foundEpisode = %s", foundEpisode);
									if(foundEpisode == false && serversSearched == plexmediaServers.size() && !videoPlayed) {
										serversSearched++;
										feedback.e(getResources().getString(R.string.couldnt_find_episode));
										return;
									}
								}

								@Override
								public void onFailure(Throwable error) {
									feedback.e(getResources().getString(R.string.got_error), error.getMessage());
								}
							});
						}
					}

					@Override
					public void onFailure(Throwable error) {
						feedback.e(getResources().getString(R.string.got_error), error.getMessage());
					}
				});
			} else {
				if(queries.size() > 0)
					startup();
				else
					feedback.e(getResources().getString(R.string.couldnt_find), queryTerm);
			}
		}
	}

	private void searchForAlbum(final String artist, final String album) {
    if(!artist.equals(""))
      feedback.m(getString(R.string.searching_for_album), album, artist);
    else
      feedback.m(getString(R.string.searching_for_the_album), album);
		Logger.d("Searching for album %s by %s.", album, artist);
		serversSearched = 0;
		Logger.d("Servers: %d", plexmediaServers.size());
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.musicSectionsSearched = 0;
					if(server.musicSections.size() == 0) {
						serversSearched++;
						if(serversSearched == plexmediaServers.size()) {
							if(albums.size() == 1) {
								playAlbum(albums.get(0));
							} else {
								if(queries.size() > 0)
									startup();
								else
									feedback.e(getResources().getString(albums.size() > 1 ? R.string.found_more_than_one_album : R.string.couldnt_find_album));
								return;
							}
						}
					}
					for(int i=0;i<server.musicSections.size();i++) {
						String section = server.musicSections.get(i);
						String path = String.format("/library/sections/%s/search?type=9&query=%s", section, album);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler()
						{
							@Override
							public void onSuccess(MediaContainer mc)
							{
								server.musicSectionsSearched++;
								for(int j=0;j<mc.directories.size();j++) {
									PlexDirectory thisAlbum = mc.directories.get(j);
									Logger.d("Album: %s by %s.", thisAlbum.title, thisAlbum.parentTitle);
									if(compareTitle(thisAlbum.title, album) || artist.equals("")) {
										Logger.d("adding album");
										thisAlbum.server = server;
										albums.add(thisAlbum);
									}
								}

								if(server.musicSections.size() == server.musicSectionsSearched) {
									serversSearched++;
									if(serversSearched == plexmediaServers.size()) {
										Logger.d("found %d albums to play.", albums.size());
										if(albums.size() == 1) {
											playAlbum(albums.get(0));
										} else {
											boolean exactMatch = false;
											for(int k=0;k<albums.size();k++) {
												if(albums.get(k).title.toLowerCase().equals(album.toLowerCase())) {
                          Logger.d("Found an exact match : %s", album);
													exactMatch = true;
													playAlbum(albums.get(k));
												}
											}
											if(!exactMatch) {
												if(queries.size() > 0)
													startup();
												else
													feedback.e(getResources().getString(albums.size() > 1 ? R.string.found_more_than_one_album : R.string.couldnt_find_album));
												return;
											}
										}
									}

								}
							}

							@Override
							public void onFailure(Throwable error) {
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if(serversSearched == plexmediaServers.size()) {
						if(albums.size() == 1) {
							playAlbum(albums.get(0));
						} else {
							if(queries.size() > 0)
								startup();
							else
								feedback.e(getResources().getString(albums.size() > 1 ? R.string.found_more_than_one_album : R.string.couldnt_find_album));
							return;
						}
					}
				}
			});
		}
	}

	private void searchForSong(final String artist, final String track) {
		serversSearched = 0;
		feedback.m(getString(R.string.searching_for_album), track, artist);
		Logger.d("Servers: %d", plexmediaServers.size());
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.musicSectionsSearched = 0;
					if(server.musicSections.size() == 0) {
						serversSearched++;
						if(serversSearched == plexmediaServers.size()) {
							if(tracks.size() > 0) {
                fetchAndPlayMedia(tracks.get(0));
							} else {
								if(queries.size() > 0)
									startup();
								else
									feedback.e(getResources().getString(R.string.couldnt_find_track));
								return;
							}
						}
					}
					for(int i=0;i<server.musicSections.size();i++) {
						String section = server.musicSections.get(i);
						String path = String.format("/library/sections/%s/search?type=10&query=%s", section, track);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler()
						{
							@Override
							public void onSuccess(MediaContainer mc)
							{
								server.musicSectionsSearched++;
								for(int j=0;j<mc.tracks.size();j++) {
									PlexTrack thisTrack = mc.tracks.get(j);
									thisTrack.artist = thisTrack.grandparentTitle;
									thisTrack.album = thisTrack.parentTitle;
									Logger.d("Track: %s by %s.", thisTrack.title, thisTrack.artist);
									if(compareTitle(thisTrack.artist, artist)) {
										thisTrack.server = server;
										tracks.add(thisTrack);
									}
								}

								if(server.musicSections.size() == server.musicSectionsSearched) {
									serversSearched++;
									if(serversSearched == plexmediaServers.size()) {
										Logger.d("found music to play.");
										if(tracks.size() > 0) {
                      fetchAndPlayMedia(tracks.get(0));
										} else {
											Boolean exactMatch = false;
											for(int k=0;k<albums.size();k++) {
												if(tracks.get(k).artist.toLowerCase().equals(artist.toLowerCase())) {
													exactMatch = true;
                          fetchAndPlayMedia(tracks.get(k));
												}
											}
											if(!exactMatch) {
												if(queries.size() > 0)
													startup();
												else
													feedback.e(getResources().getString(R.string.couldnt_find_track));
												return;
											}
										}
									}
								}
							}

							@Override
							public void onFailure(Throwable error) {
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if(serversSearched == plexmediaServers.size()) {
						if(tracks.size() > 0) {
              fetchAndPlayMedia(tracks.get(0));
						} else {
							if(queries.size() > 0)
								startup();
							else
								feedback.e(getResources().getString(R.string.couldnt_find_track));
							return;
						}
					}
				}
			});
		}
	}


	private void searchForArtist(final String artist) {
		serversSearched = 0;
		feedback.m(getString(R.string.searching_for_artist), artist);
		Logger.d("Servers: %d", plexmediaServers.size());
		for(final PlexServer server : plexmediaServers.values()) {
			server.findServerConnection(new ActiveConnectionHandler() {
				@Override
				public void onSuccess(Connection connection) {
					server.musicSectionsSearched = 0;
					if(server.musicSections.size() == 0) {
						serversSearched++;
						if(serversSearched == plexmediaServers.size()) {
							if(tracks.size() > 0) {
								fetchAndPlayMedia(tracks.get(0));
							} else {
								if(queries.size() > 0)
									startup();
								else
									feedback.e(getResources().getString(R.string.couldnt_find_track));
								return;
							}
						}
					}
					for(int i=0;i<server.musicSections.size();i++) {
						String section = server.musicSections.get(i);
						String path = String.format("/library/sections/%s/search?type=8&query=%s", section, artist);
						PlexHttpClient.get(server, path, new PlexHttpMediaContainerHandler()
						{
							@Override
							public void onSuccess(MediaContainer mc)
							{
								server.musicSectionsSearched++;
                PlexDirectory theFoundArtist = null;
								for(int j=0;j<mc.directories.size();j++) {
                  PlexDirectory thisArtist = mc.directories.get(j);
//									thisTrack.artist = thisTrack.grandparentTitle;
//									thisTrack.album = thisTrack.parentTitle;
									Logger.d("Artist: %s.", thisArtist.title);
									if(compareTitle(thisArtist.title, artist)) {
                    thisArtist.server = server;
                    theFoundArtist = thisArtist;
                    break;
									}
								}
                if(theFoundArtist != null)
                  foundArtist(theFoundArtist);
                else
                  feedback.e(String.format(getResources().getString(R.string.couldnt_find_artist), artist));
							}

              public void foundArtist(PlexDirectory thisArtist) {
                getAllTracksFromArtist(thisArtist, new Runnable() {
                  @Override
                  public void run() {
                    if(server.musicSections.size() == server.musicSectionsSearched) {
                      serversSearched++;
                      if(serversSearched == plexmediaServers.size()) {
                        Logger.d("found music to play.");
                        if(tracks.size() > 0) {
                          fetchAndPlayMedia(tracks.get(0));
                          return;
                        } else {
                          Boolean exactMatch = false;
                          for(int k=0;k<albums.size();k++) {
                            if(tracks.get(k).artist.toLowerCase().equals(artist.toLowerCase())) {
                              exactMatch = true;
                              playMedia(tracks.get(0));
//                              fetchAndPlayMedia(tracks.get(k));
                            }
                          }
                          if(!exactMatch) {
                            if(queries.size() > 0)
                              startup();
                            else
                              feedback.e(getResources().getString(R.string.couldnt_find_track));
                            return;
                          }
                        }
                      }
                    }
                  }
                });
              }

							@Override
							public void onFailure(Throwable error) {
								feedback.e(getResources().getString(R.string.got_error), error.getMessage());
							}
						});
					}
				}

				@Override
				public void onFailure(int statusCode) {
					serversSearched++;
					if(serversSearched == plexmediaServers.size()) {
						if(tracks.size() > 0) {
							fetchAndPlayMedia(tracks.get(0));
						} else {
							if(queries.size() > 0)
								startup();
							else
								feedback.e(getResources().getString(R.string.couldnt_find_track));
							return;
						}
					}
				}
			});
		}
	}

  private void getAllTracksFromArtist(final PlexDirectory artist, final Runnable onFinished) {
    String path = String.format("%s", artist.key);
    PlexHttpClient.get(artist.server, path, new PlexHttpMediaContainerHandler()
    {
      final int[] numAlbumsToQuery = new int[1];
      final int[] numAlbumsQueried = new int[1];
      @Override
      public void onSuccess(MediaContainer mediaContainer) {
        numAlbumsToQuery[0] = mediaContainer.directories.size();
        numAlbumsQueried[0] = 0;
        Logger.d("numAlbumsToQuery: %d", numAlbumsToQuery[0]);
        for(int i=0;i<numAlbumsToQuery[0];i++) {
          PlexDirectory album = mediaContainer.directories.get(i);

          PlexHttpClient.get(artist.server, String.format("%s", album.key), new PlexHttpMediaContainerHandler() {
            @Override
            public void onSuccess(MediaContainer mediaContainer) {
              for(int j=0;j<mediaContainer.tracks.size();j++) {
                mediaContainer.tracks.get(j).artist = mediaContainer.tracks.get(j).grandparentTitle;
                mediaContainer.tracks.get(j).server = artist.server;
                tracks.add(mediaContainer.tracks.get(j));
                Logger.d("Added %s by %s", mediaContainer.tracks.get(j).title, mediaContainer.tracks.get(j).artist);
              }
              numAlbumsQueried[0]++;
              Logger.d("Num albums queried: %d", numAlbumsQueried[0]);
              if(numAlbumsQueried[0] == numAlbumsToQuery[0]) {
                Logger.d("Done adding tracks.");
                onAllAlbumsQueried();
              }

            }

            @Override
            public void onFailure(Throwable error) {
              // TODO: Handle failure
              numAlbumsQueried[0]++;
              if(numAlbumsQueried[0] == numAlbumsToQuery[0]) {
                Logger.d("Done adding tracks.");
                onAllAlbumsQueried();
              }
            }
          });

        }
      }

      public void onAllAlbumsQueried() {
        if(shuffle) {
          Collections.shuffle(tracks, new Random(System.nanoTime()));
        }
        onFinished.run();
      }

      @Override
      public void onFailure(Throwable error) {
        error.printStackTrace();
        // TODO: Handle failure
        numAlbumsQueried[0]++;
        if(numAlbumsQueried[0] == numAlbumsToQuery[0]) {
          onAllAlbumsQueried();
        }
      }
    });
  }

	private void playAlbum(final PlexDirectory album) {
    Logger.d("[PlexSearchService] playing album %s", album.key);
		PlexHttpClient.get(album.server, album.key, new PlexHttpMediaContainerHandler()
		{
			@Override
			public void onSuccess(MediaContainer mc)
			{
				if(mc.tracks.size() > 0) {
          List<PlexTrack> tracks = mc.tracks;
          for(PlexTrack track : tracks) {
            track.server = album.server;
            track.thumb = album.thumb;
            track.grandparentTitle = album.parentTitle;
            track.parentTitle = album.title;
            track.art = album.art;
            track.grandparentKey = album.parentKey;
          }


          if(shuffle) {
            Collections.shuffle(tracks);
          }
          if(client.isCastClient)
            castAlbum(tracks);
          else
  					playMedia(tracks.get(0), album);
				} else {
					Logger.d("Didn't find any tracks");
					if(queries.size() > 0)
						startup();
					else
						feedback.e(getResources().getString(R.string.couldnt_find_album));
					return;
				}
			}

			@Override
			public void onFailure(Throwable error) {
				feedback.e(getResources().getString(R.string.got_error), error.getMessage());
			}
		});
	}

	private void showPlayingTrack(PlexTrack track) {
		Intent nowPlayingIntent = new Intent(this, NowPlayingActivity.class);
		nowPlayingIntent.putExtra(com.atomjack.shared.Intent.EXTRA_MEDIA, track);
		nowPlayingIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLIENT, client);
		nowPlayingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(nowPlayingIntent);
	}

	private class MediaRouterCallback extends MediaRouter.Callback {
		@Override

		public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route)
		{
			Logger.d("onRouteAdded: %s", route);
		}
		@Override
		public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo route) {
			Logger.d("onRouteSelected: %s", route);
//			MainActivity.this.onRouteSelected(route);
		}
		@Override
		public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo route) {
			Logger.d("onRouteUnselected: %s", route);
//			MainActivity.this.onRouteUnselected(route);
		}
	}


	private class ConnectionCallbacks implements
					GoogleApiClient.ConnectionCallbacks {
		@Override
		public void onConnected(Bundle connectionHint) {
			if (mWaitingForReconnect) {
				mWaitingForReconnect = false;
				// TODO: uhhh
				//reconnectChannels();
			} else {
				try {
					Cast.CastApi.launchApplication(mApiClient, BuildConfig.CHROMECAST_APP_ID, false)
									.setResultCallback(
													new ResultCallback<Cast.ApplicationConnectionResult>() {
														@Override
														public void onResult(Cast.ApplicationConnectionResult result) {
															Status status = result.getStatus();
															if (status.isSuccess()) {
																ApplicationMetadata applicationMetadata =
																				result.getApplicationMetadata();
																String sessionId = result.getSessionId();
																String applicationStatus = result.getApplicationStatus();
																boolean wasLaunched = result.getWasLaunched();
//																...
															} else {
																//teardown();
															}
														}
													});

				} catch (Exception e) {
					Logger.d("Failed to launch application", e);
				}
			}
		}

		@Override
		public void onConnectionSuspended(int cause) {
			mWaitingForReconnect = true;
		}
	}

	private class ConnectionFailedListener implements
					GoogleApiClient.OnConnectionFailedListener {
		@Override
		public void onConnectionFailed(ConnectionResult result) {
			// TODO: uhhh
			//teardown();
		}
	}

  private void sendClientScanIntent() {
    Logger.d("Scanning for clients");
    Intent scannerIntent = new Intent(PlexSearchService.this, PlexScannerService.class);
    scannerIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    scannerIntent.putExtra(PlexScannerService.CLASS, PlexSearchService.class);
    scannerIntent.setAction(PlexScannerService.ACTION_SCAN_CLIENTS);
    startService(scannerIntent);
  }

  private void onActionFinished(String action, boolean error, PlexMedia media) {
    if(fromWear) {
      Logger.d("[PlexSearchService] onActionFinished: %s", action);

      DataMap dataMap = new DataMap();
      dataMap.putBoolean(WearConstants.SPEECH_QUERY_RESULT, !error);


      new SendToDataLayerThread(action, dataMap, this).start();
    }
  }

  // Feedback class that will also send a message to a connected wear device
  private class SearchFeedback extends Feedback {
    public SearchFeedback(Context ctx) {
      super(ctx);
    }

    @Override
    protected void feedback(String text, boolean error) {
      super.feedback(text, error);
      if(VoiceControlForPlexApplication.getInstance().hasWear() && error) {
        DataMap dataMap = new DataMap();
        dataMap.putString(WearConstants.INFORMATION, text);
        new SendToDataLayerThread(WearConstants.SET_INFO, dataMap, PlexSearchService.this).start();
      }
    }
  }
}
