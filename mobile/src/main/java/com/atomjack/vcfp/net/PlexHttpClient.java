package com.atomjack.vcfp.net;

import android.util.Base64;

import com.atomjack.shared.Logger;
import com.atomjack.shared.PlayerState;
import com.atomjack.shared.Preferences;
import com.atomjack.vcfp.PlexHeaders;
import com.atomjack.vcfp.R;
import com.atomjack.vcfp.VoiceControlForPlexApplication;
import com.atomjack.vcfp.exceptions.UnauthorizedException;
import com.atomjack.vcfp.interfaces.ActiveConnectionHandler;
import com.atomjack.vcfp.interfaces.GenericHandler;
import com.atomjack.vcfp.interfaces.InputStreamHandler;
import com.atomjack.vcfp.interfaces.PlexDirectoryHandler;
import com.atomjack.vcfp.interfaces.PlexMediaHandler;
import com.atomjack.vcfp.interfaces.PlexPlayQueueHandler;
import com.atomjack.vcfp.model.Connection;
import com.atomjack.vcfp.model.MediaContainer;
import com.atomjack.vcfp.model.Pin;
import com.atomjack.vcfp.model.PlexClient;
import com.atomjack.vcfp.model.PlexDirectory;
import com.atomjack.vcfp.model.PlexMedia;
import com.atomjack.vcfp.model.PlexResponse;
import com.atomjack.vcfp.model.PlexServer;
import com.atomjack.vcfp.model.PlexUser;
import com.atomjack.vcfp.model.Stream;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.ResponseBody;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import retrofit.Call;
import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;
import retrofit.RxJavaCallAdapterFactory;
import retrofit.SimpleXmlConverterFactory;
import retrofit.http.GET;
import retrofit.http.Headers;
import retrofit.http.POST;
import retrofit.http.PUT;
import retrofit.http.Path;
import retrofit.http.Query;
import retrofit.http.QueryMap;
import retrofit.http.Url;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;


public class PlexHttpClient
{
  private static OkHttpClient httpClient = new OkHttpClient();

  public interface PlexHttpService {
    @GET("/library/sections/{section}/search")
    Call<MediaContainer> searchSection(@Path("section") String section,
                                       @Query("type") String type,
                                       @Query("query") String query,
                                       @Query(PlexHeaders.XPlexToken) String token);

    @GET
    Call<MediaContainer> getMediaContainer(@Url String path, @Query(PlexHeaders.XPlexToken) String token);

    @GET
    Observable<MediaContainer> newGetMediaContainer(@Url String path, @Query(PlexHeaders.XPlexToken) String token);

    @GET
    Call<PlexResponse> getPlexResponse(@retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId,
                                       @Url String path);

    @GET("/player/timeline/subscribe?protocol=http")
    Call<PlexResponse> subscribe(@retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId,
                                 @retrofit.http.Header(PlexHeaders.XPlexDeviceName) String deviceName,
                                 @Query("port") int subscriptionPort,
                                 @Query("commandID") int commandId);

    @GET("/player/timeline/unsubscribe")
    Call<PlexResponse> unsubscribe(@retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId,
                                   @retrofit.http.Header(PlexHeaders.XPlexDeviceName) String deviceName,
                                   @retrofit.http.Header(PlexHeaders.XPlexTargetClientIdentifier) String machineIdentifier);

    @retrofit.http.Headers(PlexHeaders.XPlexDeviceName + ": Voice Control for Plex")
    @GET("/player/timeline/poll")
    Call<MediaContainer> pollTimeline(@retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId,
                                 @Query("commandID") int commandId);

    @GET("/pins/{pinID}.xml")
    Call<Pin> fetchPin(@Path(value="pinID", encoded = true) int pinID, @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @GET("/pins/{pinID}.xml")
    Observable<Pin> newFetchPin(@Path(value="pinID", encoded = true) int pinID, @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @Headers("Accept: text/xml")
    @POST("/users/sign_in.xml")
    Call<PlexUser> signin(@retrofit.http.Header(PlexHeaders.XPlexClientPlatform) String clientPlatform,
                          @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId,
                          @Query("auth_token") String authToken);

    @POST("/pins.xml")
    Call<Pin> getPinCode(@retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @POST("/pins.xml")
    Observable<Pin> newGetPinCode(@retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @POST("/playQueues")
    Call<MediaContainer> createPlayQueue(@QueryMap Map<String, String> options, @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @GET("/player/playback/{which}")
    Call<PlexResponse> adjustPlayback(@Path(value="which", encoded=true) String which,
                                      @Query("commandID") String commandId,
                                      @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @GET("/player/playback/seekTo")
    Call<PlexResponse> seekTo(@Query("offset") int offset,
                              @Query(PlexHeaders.commandID) String commandId,
                              @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @GET("/library/sections")
    Call<MediaContainer> getLibrarySections(@Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/pms/resources")
    Call<MediaContainer> getResources(@Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/player/playback/setStreams")
    Call<PlexResponse> setStreams(@QueryMap Map<String, String> options,
                                  @Query(PlexHeaders.commandID) String commandId,
                                  @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);

    @GET("/users/account.xml")
    Call<PlexUser> getPlexAccount(@retrofit.http.Header(PlexHeaders.XPlexToken) String authToken);

    @GET("/library/sections/{section}/all")
    Observable<MediaContainer> getRandomMovie(@Path(value="section", encoded=true) String section,
                                              @Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/library/sections/{section}/all")
    Observable<MediaContainer> getRandomShow(@Path(value="section", encoded=true) String section,
                                              @Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/library/onDeck/all")
    Observable<MediaContainer> getRandomOnDeck(@Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/library/sections/{section}/all")
    Call<MediaContainer> getRandomDirectory(@Path(value="section", encoded=true) String section,
                                            @Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/library/sections/{section}/all")
    Observable<MediaContainer> newGetRandomDirectory(@Path(value="section", encoded=true) String section,
                                            @Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/library/metadata/{ratingKey}/allLeaves")
    Call<MediaContainer> getRandomEpisode(@Path(value="ratingKey", encoded=true) String ratingKey,
                                          @Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/library/metadata/{ratingKey}/allLeaves")
    Observable<MediaContainer> newGetRandomEpisode(@Path(value="ratingKey", encoded=true) String ratingKey,
                                          @Query(PlexHeaders.XPlexToken) String accessToken);

    @GET("/{type}/:/transcode/universal/stop")
    Call<PlexResponse> stopTranscoder(@Path(value="type", encoded = true) String type,
                                      @Query(PlexHeaders.XPlexToken) String token,
                                      @Query(PlexHeaders.session) String session,
                                      @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId);


    @GET("/:/timeline/")
    Call<PlexResponse> reportProgressToServer(@Query(PlexHeaders.XPlexToken) String token,
                                              @Query(PlexHeaders.key) String key,
                                              @Query(PlexHeaders.ratingKey) String ratingKey,
                                              @Query(PlexHeaders.time) String time,
                                              @Query(PlexHeaders.duration) String duration,
                                              @Query(PlexHeaders.state) String state,
                                              @retrofit.http.Header(PlexHeaders.XPlexClientIdentifier) String clientId,
                                              @retrofit.http.Header(PlexHeaders.XPlexDeviceName) String deviceName,
                                              @retrofit.http.Header(PlexHeaders.XPlexPlatform) String platform,
                                              @retrofit.http.Header(PlexHeaders.XPlexProduct) String product);

    @PUT("/library/parts/{part_id}?allParts=1")
    Call<PlexResponse> setSubtitleStreamActive(@Path(value="part_id", encoded = true) String partId,
                                               @Query("subtitleStreamID") String streamId,
                                               @Query(PlexHeaders.XPlexToken) String token);

    @PUT("/library/parts/{part_id}")
    Call<PlexResponse> setAudioStreamActive(@Path(value="part_id", encoded = true) String partId,
                                            @Query("audioStreamID") String streamId,
                                            @Query(PlexHeaders.XPlexToken) String token);

    @GET("/library/metadata/{key}/children")
    Call<MediaContainer> getChildren(@Path(value="key", encoded = true) String key,
                                     @Query(PlexHeaders.XPlexToken) String token);

    @GET("/library/sections/{section}/search?type=2")
    Observable<MediaContainer> searchForShow(@Path(value="section", encoded = true) String section,
                                             @Query("query") String query,
                                             @Query(PlexHeaders.XPlexToken) String token);

    @GET("/library/sections/{section}/onDeck")
    Observable<MediaContainer> getOnDeck(@Path(value="section", encoded=true) String section,
                                         @Query(PlexHeaders.XPlexToken) String token);

    @GET("/library/sections/{section}/recentlyAdded")
    Observable<MediaContainer> getRecentlyAdded(@Path(value="section", encoded=true) String section,
                                         @Query(PlexHeaders.XPlexToken) String token);
  }

  public static void getThumb(String url, final InputStreamHandler inputStreamHandler) {
    Request request = new Request.Builder()
            .url(url)
            .build();
    httpClient.newCall(request).enqueue(new com.squareup.okhttp.Callback() {
      @Override
      public void onFailure(Request request, IOException e) {
        e.printStackTrace();
      }

      @Override
      public void onResponse(com.squareup.okhttp.Response response) throws IOException {
        Logger.d("got %d bytes", response.body().contentLength());
        inputStreamHandler.onSuccess(response.body().byteStream());
      }
    });
  }

  public static PlexHttpService getService(Connection connection) {
    return getService(connection.uri);
  }

  public static PlexHttpService getService(Connection connection, int timeout) {
    return getService(connection.uri, null, null, false, timeout);
  }

  public static PlexHttpService getService(Connection connection, boolean debug) {
    return getService(connection.uri, debug);
  }

  public static PlexHttpService getService(String url) {
    return getService(url, false);
  }

  public static PlexHttpService getService(String url, boolean debug) {
    return getService(url, null, null, debug);
  }

  public static PlexHttpService getService(String url, String username, String password) {
    return getService(url, username, password, false);
  }

  public static PlexHttpService getService(String url, String username, String password, boolean debug) {
    return getService(url, username, password, debug, 0);
  }

  public static PlexHttpService getService(String url, String username, String password, boolean debug, int timeout) {
    OkHttpClient client = new OkHttpClient();
    if(timeout > 0)
      client.setReadTimeout(timeout, TimeUnit.SECONDS);
    Retrofit.Builder builder = new Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .addConverterFactory(SimpleXmlConverterFactory.create());

    if(username != null && password != null) {
      String creds = username + ":" + password;
      final String basic = "Basic " + Base64.encodeToString(creds.getBytes(), Base64.NO_WRAP);
      client.interceptors().add(chain -> {
        Request original = chain.request();

        Request.Builder requestBuilder = original.newBuilder()
                .header("Authorization", basic)
        .header("Accept", "text/xml")
        .method(original.method(), original.body());

        Request request = requestBuilder.build();
        return chain.proceed(request);
      });
    }
    if(debug) {
      client.interceptors().add(chain -> {
        try {
          com.squareup.okhttp.Response response = chain.proceed(chain.request());
          ResponseBody responseBody = response.body();
          String body = response.body().string();
          Logger.d("Retrofit@Response: (%d) %s", response.code(), body);
          return response.newBuilder().body(ResponseBody.create(responseBody.contentType(), body.getBytes())).build();
        } catch (Exception e) {
          e.printStackTrace();
        }
        return null;
      });
    }

    // Plex Media Player currently returns an empty body or "Failure: 200 OK" instead of valid XML for many calls, so we must detect an empty body
    // and write our own valid XML in place of it
    client.interceptors().add(chain -> {
      try {
        com.squareup.okhttp.Response response = chain.proceed(chain.request());
        ResponseBody responseBody = response.body();
        String body = response.body().string();
        if(body.equals("") || body.matches(".*Failure: 200 OK\r\n.*")) {
          body = String.format("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                  "<Response code=\"%d\" status=\"%s\" />", response.code(), response.code() == 200 ? "OK" : "Error");
        }
        return response.newBuilder().body(ResponseBody.create(responseBody.contentType(), body.getBytes())).build();
      } catch (Exception e) {
        e.printStackTrace();
      }


      return null;
    });

    Retrofit retrofit = builder.client(client).build();
    return retrofit.create(PlexHttpService.class);
  }

  public static void searchServer(final PlexServer server, final String section, final String queryTerm, final PlexHttpMediaContainerHandler responseHandler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection.uri);
        Call<MediaContainer> call = service.searchSection(section, "1", queryTerm, server.accessToken);
        call.enqueue(new Callback<MediaContainer>() {
          @Override
          public void onResponse(Response<MediaContainer> response, Retrofit retrofit) {
            responseHandler.onSuccess(response.body());
          }

          @Override
          public void onFailure(Throwable t) {
            if (responseHandler != null)
              responseHandler.onFailure(t);
          }
        });
      }

      @Override
      public void onFailure(int statusCode) {
        if (responseHandler != null)
          responseHandler.onFailure(new Throwable());
      }
    });
  }

  public static void getDebug(final PlexServer server, final String path, final PlexHttpMediaContainerHandler responseHandler) {
    get(server, path, true, responseHandler);
  }

  public static void get(final PlexServer server, final String path, final PlexHttpMediaContainerHandler responseHandler) {
    get(server, path, false, responseHandler);
  }

	public static void get(final PlexServer server, final String path, final boolean debug, final PlexHttpMediaContainerHandler responseHandler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {

        PlexHttpService service = getService(connection.uri, debug);
        Call<MediaContainer> call = service.getMediaContainer(path.substring(1), server.accessToken);
        call.enqueue(new Callback<MediaContainer>() {
          @Override
          public void onResponse(Response<MediaContainer> response, Retrofit retrofit) {
            try {
              // Add this server to each of this media container's media objects
              MediaContainer mediaContainer = response.body();
              if (mediaContainer.tracks != null) {
                for (int i = 0; i < mediaContainer.tracks.size(); i++) {
                  mediaContainer.tracks.get(i).server = server;
                }
              }
              if (mediaContainer.videos != null) {
                for (int i = 0; i < mediaContainer.videos.size(); i++) {
                  mediaContainer.videos.get(i).server = server;
                }
              }
              responseHandler.onSuccess(response.body());
            } catch (Exception e) {
              responseHandler.onFailure(e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            if (responseHandler != null)
              responseHandler.onFailure(t);
          }
        });
      }

      @Override
      public void onFailure(int statusCode) {
        if (responseHandler != null)
          responseHandler.onFailure(statusCode == 401 ? new UnauthorizedException() : new Throwable());
      }
    });
  }

  public static void subscribe(PlexClient client, int subscriptionPort, int commandId, String uuid, String deviceName, final PlexHttpResponseHandler responseHandler) {
    String url = String.format("http://%s:%s", client.address, client.port);
    PlexHttpService service = getService(url);

    Call<PlexResponse> call = service.subscribe(uuid, deviceName, subscriptionPort, commandId);
    call.enqueue(new Callback<PlexResponse>() {
      @Override
      public void onResponse(Response<PlexResponse> response, Retrofit retrofit) {
        if (responseHandler != null) {
          Logger.d("Response code: %d", response.code());
          if(response.code() == 200)
            responseHandler.onSuccess(response.body());
          else
            responseHandler.onFailure(new Throwable());
        }
      }

      @Override
      public void onFailure(Throwable t) {
        if(t.getMessage().matches(".*Failure: 200 OK\n.*")) {
          if (responseHandler != null) {
            PlexResponse response = new PlexResponse();
            response.code = 200;
            response.status = "ok";
            responseHandler.onSuccess(response);
          }
        } else {
          Logger.d("subscribe onFailure: %s", t.getMessage());

          PlexResponse response = new PlexResponse();
          response.status = "ok";

          t.printStackTrace();
          if (responseHandler != null)
            responseHandler.onFailure(t);
        }
      }
    });
  }

  public static void unsubscribe(PlexClient client, int commandId, String uuid, String deviceName, final PlexHttpResponseHandler responseHandler) {
    String url = String.format("http://%s:%s", client.address, client.port);
    PlexHttpService service = getService(url);
    Call<PlexResponse> call = service.unsubscribe(uuid, deviceName, client.machineIdentifier);
    call.enqueue(new Callback<PlexResponse>() {
      @Override
      public void onResponse(Response<PlexResponse> response, Retrofit retrofit) {
        responseHandler.onSuccess(response.body());
      }

      @Override
      public void onFailure(Throwable t) {
        responseHandler.onFailure(t);
      }
    });

  }

  public static void createArtistPlayQueue(Connection connection, PlexDirectory artist, final PlexPlayQueueHandler responseHandler) {
    HashMap<String, String> qs = new HashMap<>();
    qs.put("type", "audio");
    qs.put("shuffle", "1");
    String uri = String.format("library://%s/item/%%2flibrary%%2fmetadata%%2f%s", artist.server.machineIdentifier, artist.ratingKey);
    Logger.d("URI: %s", uri);
    qs.put("uri", uri);
    if(artist.server.accessToken != null)
      qs.put(PlexHeaders.XPlexToken, artist.server.accessToken);
    qs.put("continuous", "0");
    qs.put("includeRelated", "1");
    PlexHttpService service = getService(String.format("http://%s:%s", connection.address, connection.port), true);
    Call<MediaContainer> call = service.createPlayQueue(qs, VoiceControlForPlexApplication.getUUID());
    call.enqueue(new Callback<MediaContainer>() {
      @Override
      public void onResponse(Response<MediaContainer> response, Retrofit retrofit) {
        if (responseHandler != null)
          responseHandler.onSuccess(response.body());
      }

      @Override
      public void onFailure(Throwable t) {
        Logger.d("createPlayQueue failure.");
        t.printStackTrace();
      }
    });
  }

  public static void createPlayQueue(Connection connection, final PlexMedia media, boolean resume, final String key, String transientToken, final PlexPlayQueueHandler responseHandler) {
    Map<String, String> qs = new HashMap<>();
    qs.put("type", media.getType());
    qs.put("next", "0");

    boolean hasOffset = (VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.RESUME, false)|| resume) && media.viewOffset != null;//media.viewOffset != null && Integer.parseInt(media.viewOffset) > 0;
    if(media.isMovie() && !hasOffset) {
      qs.put("extrasPrefixCount", Integer.toString(VoiceControlForPlexApplication.getInstance().prefs.get(Preferences.NUM_CINEMA_TRAILERS, 0)));
    }

    if(hasOffset)
      qs.put("viewOffset", media.viewOffset);

    String uri = String.format("library://%s/item/%%2flibrary%%2fmetadata%%2f%s", media.server.machineIdentifier, key);
    qs.put("uri", uri);
    qs.put("window", "50"); // no idea what this is for
    if (transientToken != null)
      qs.put("token", transientToken);
    if (media.server.accessToken != null)
      qs.put(PlexHeaders.XPlexToken, media.server.accessToken);

    PlexHttpService service = getService(String.format("http://%s:%s", connection.address, connection.port));
    Call<MediaContainer> call = service.createPlayQueue(qs, VoiceControlForPlexApplication.getUUID());
    call.enqueue(new Callback<MediaContainer>() {
      @Override
      public void onResponse(Response<MediaContainer> response, Retrofit retrofit) {
        if (responseHandler != null) {
          MediaContainer mc = response.body();
          for(int i=0;i<mc.tracks.size();i++) {
            mc.tracks.get(i).server = media.server;
          }
          for(int i=0;i<mc.videos.size();i++) {
            mc.videos.get(i).server = media.server;
            if (mc.videos.get(i).isClip())
              mc.videos.get(i).setClipDuration();
          }
          responseHandler.onSuccess(mc);
        }
      }

      @Override
      public void onFailure(Throwable t) {
        Logger.d("createPlayQueue failure.");
        t.printStackTrace();
      }
    });
  }

  public static void getDebug(String baseHostname, String path, final PlexHttpResponseHandler responseHandler) {
    get(baseHostname, path, true, responseHandler);
  }

  public static void get(String baseHostname, String path, final PlexHttpResponseHandler responseHandler) {
    get(baseHostname, path, false, responseHandler);
  }

  public static void get(String baseHostname, String path, boolean debug, final PlexHttpResponseHandler responseHandler) {
    PlexHttpService service = getService(baseHostname, debug);
    Call<PlexResponse> call = service.getPlexResponse(VoiceControlForPlexApplication.getInstance().prefs.getUUID(),
            path.replaceFirst("^/", ""));
    call.enqueue(new Callback<PlexResponse>() {
      @Override
      public void onResponse(Response<PlexResponse> response, Retrofit retrofit) {
        if (responseHandler != null)
          responseHandler.onSuccess(response.body());
      }

      @Override
      public void onFailure(Throwable t) {
        if (responseHandler != null)
          responseHandler.onFailure(t);
      }
    });
  }

	public static void getPinCode(final PlexPinResponseHandler responseHandler) {
    PlexHttpService service = getService("https://plex.tv:443");
    service.newGetPinCode(VoiceControlForPlexApplication.getUUID())
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(pin -> responseHandler.onSuccess(pin), t -> responseHandler.onFailure(t));
	}

  public static void signin(String authToken, final PlexHttpUserHandler responseHandler) {
    signin(null, null, authToken, responseHandler);
  }

  public static void signin(String username, String password, final PlexHttpUserHandler responseHandler) {
    signin(username, password, null, responseHandler);
  }

  public static void signin(String username, String password, String authToken, final PlexHttpUserHandler responseHandler) {
    PlexHttpService service = getService("https://plex.tv", username, password, false);
    Call<PlexUser> call = service.signin("Android", VoiceControlForPlexApplication.getUUID(), authToken);
    call.enqueue(new Callback<PlexUser>() {
      @Override
      public void onResponse(Response<PlexUser> response, Retrofit retrofit) {
        if(response.code() == 200 || response.code() == 201)
          responseHandler.onSuccess(response.body());
        else {
          responseHandler.onFailure(response.code());
        }
      }

      @Override
      public void onFailure(Throwable t) {
        t.printStackTrace();
        responseHandler.onFailure(0);
      }
    });
  }

  public static byte[] getSyncBytes(String url) throws SocketTimeoutException {
    Request request = new Request.Builder()
            .url(url)
            .build();
    try {
      com.squareup.okhttp.Response response = httpClient.newCall(request).execute();
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
      return response.body().bytes();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
    return null;
  }

  public static void getClientTimeline(PlexClient client, final int commandId, final PlexHttpMediaContainerHandler responseHandler) {
    String url = String.format("http://%s:%s", client.address, client.port);
    PlexHttpService service = getService(url);
    Logger.d("Polling timeline with uuid %s", VoiceControlForPlexApplication.getInstance().prefs.getUUID());
    Call<MediaContainer> call = service.pollTimeline(VoiceControlForPlexApplication.getInstance().prefs.getUUID(), commandId);
    call.enqueue(new Callback<MediaContainer>() {
      @Override
      public void onResponse(Response<MediaContainer> response, Retrofit retrofit) {
        responseHandler.onSuccess(response.body());
      }

      @Override
      public void onFailure(Throwable t) {
        responseHandler.onFailure(t);
      }
    });
  }

  public static void fetchPin(int pinID, final PlexPinResponseHandler responseHandler) {
    String url = "https://plex.tv:443";
    PlexHttpService service = getService(url);
    service.newFetchPin(pinID, VoiceControlForPlexApplication.getInstance().prefs.getUUID())
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(s -> responseHandler.onSuccess(s), s -> responseHandler.onFailure(new Throwable()));
  }

  public static void getPlexAccount(String authToken, final PlexHttpUserHandler responseHandler) {
    String url = "https://plex.tv:443";
    PlexHttpService service = getService(url);
    Call<PlexUser> call = service.getPlexAccount(authToken);
    call.enqueue(new Callback<PlexUser>() {
      @Override
      public void onResponse(Response<PlexUser> response, Retrofit retrofit) {
        if(responseHandler != null)
          responseHandler.onSuccess(response.body());
      }

      @Override
      public void onFailure(Throwable t) {

      }
    });
  }

  public static void getRandomMovie(final PlexServer server, final PlexMediaHandler onFinish) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        service.getRandomMovie(server.getRandomMovieSection(), server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(mc -> mc.getRandomVideo())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(video -> onFinish.onFinish(video), e -> onFinish.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {
        if(onFinish != null)
          onFinish.onFinish(null);
      }
    });
  }

  public static void getRandomShow(final PlexServer server, final PlexDirectoryHandler handler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        service.getRandomShow(server.getRandomTvSection(), server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(mc -> mc.getRandomDirectory())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(show -> handler.onFinish(show), e -> handler.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {

      }
    });
  }

  public static void getRandomOnDeck(final PlexServer server, final PlexMediaHandler onFinish) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        service.getRandomOnDeck(server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(mc -> mc.getRandomVideo())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(video -> onFinish.onFinish(video), e -> onFinish.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {
        if(onFinish != null)
          onFinish.onFinish(null);
      }
    });
  }

  public static void getRandomEpisode(final PlexServer server,
                                      final Connection connection,
                                      final String showSpecified,
                                      final String section,
                                      final PlexMediaHandler handler) {
    final PlexHttpService service = getService(connection);
    service.searchForShow(section, showSpecified, server.accessToken)
            .subscribeOn(Schedulers.io())
            .filter(shows -> shows != null)
            .map(shows -> shows.getRandomDirectory())
            .flatMap(show -> service.newGetRandomEpisode(show.ratingKey, server.accessToken))
            .map(episodes -> episodes.getRandomVideo())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(video -> handler.onFinish(video), e -> handler.onFinish(null));
  }

  public static void getRandomEpisode(final PlexServer server, final PlexMediaHandler onFinish) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(final Connection connection) {
        final PlexHttpService service = getService(connection);
        service.newGetRandomDirectory(server.getRandomTvSection(), server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(shows -> shows.getRandomDirectory())
                .flatMap(show -> service.newGetRandomEpisode(show.ratingKey, server.accessToken))
                .map(episodes -> episodes.getRandomVideo())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(video -> onFinish.onFinish(video), e -> onFinish.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {
        if(onFinish != null)
          onFinish.onFinish(null);
      }
    });
  }

  public static void getRandomSong(final PlexServer server, final PlexMediaHandler onFinish) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        final PlexHttpService service = getService(connection);
        String section = server.musicSections.get(new Random().nextInt(server.musicSections.size()));

        service.newGetRandomDirectory(section, server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(artists -> artists.getRandomDirectory())
                .flatMap(artist -> service.newGetMediaContainer(artist.key, server.accessToken))
                .map(albums -> albums.getRandomDirectory())
                .flatMap(album -> service.newGetMediaContainer(album.key, server.accessToken))
                .map(songs -> songs.getRandomTrack())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(track -> onFinish.onFinish(track), e -> onFinish.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {
        if(onFinish != null)
          onFinish.onFinish(null);
      }
    });
  }

  public static void getRandomAlbum(final PlexServer server, final PlexDirectoryHandler onFinish) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        final PlexHttpService service = getService(connection);
        service.newGetRandomDirectory(server.getRandomMusicSection(), server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(artists -> artists.getRandomDirectory())
                .flatMap(artist -> service.newGetMediaContainer(artist.key, server.accessToken))
                .map(albums -> albums.getRandomDirectory())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(album -> onFinish.onFinish(album), e -> onFinish.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {
        if(onFinish != null)
          onFinish.onFinish(null);
      }
    });
  }

  public static void getRandomArtist(final PlexServer server, final PlexDirectoryHandler onFinish) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        final PlexHttpService service = getService(connection);
        service.newGetRandomDirectory(server.getRandomMusicSection(), server.accessToken)
                .subscribeOn(Schedulers.io())
                .map(artists -> artists.getRandomDirectory())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(artist -> onFinish.onFinish(artist), e -> onFinish.onFinish(null));
      }

      @Override
      public void onFailure(int statusCode) {
        if(onFinish != null)
          onFinish.onFinish(null);
      }
    });
  }

  public static void stopTranscoder(final PlexServer server, final String session, final String type) {
    stopTranscoder(server, session, type, null);
  }

  public static void stopTranscoder(final PlexServer server, final String session, final String type, final GenericHandler handler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection.uri);
        Call<PlexResponse> call = service.stopTranscoder(type, server.accessToken, session, VoiceControlForPlexApplication.getInstance().prefs.getUUID());
        call.enqueue(new Callback<PlexResponse>() {
          @Override
          public void onResponse(Response<PlexResponse> response, Retrofit retrofit) {
            Logger.d("Stopped transcoder");
            if(handler != null)
              handler.onSuccess();
          }

          @Override
          public void onFailure(Throwable t) {
            if(handler != null)
              handler.onFailure();
          }
        });
      }

      @Override
      public void onFailure(int statusCode) {
        if(handler != null)
          handler.onFailure();
      }
    });

  }

  public static void reportProgressToServer(final PlexMedia media, final int time, final PlayerState state) {
    media.server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection.uri);
        Call<PlexResponse> call = service.reportProgressToServer(
                media.server.accessToken,
                media.key,
                media.ratingKey,
                Integer.toString(time),
                Integer.toString(media.duration),
                state.toStateString(),
                VoiceControlForPlexApplication.getInstance().prefs.getUUID(),
                VoiceControlForPlexApplication.getInstance().getString(R.string.app_name),
                "Android",
                VoiceControlForPlexApplication.getInstance().getString(R.string.app_name)
        );
        call.enqueue(new Callback<PlexResponse>() {
          @Override
          public void onResponse(Response<PlexResponse> response, Retrofit retrofit) {
//            Logger.d("Done reporting");
          }

          @Override
          public void onFailure(Throwable t) {
            // TODO: Handle
          }
        });
      }

      @Override
      public void onFailure(int statusCode) {
        // TODO: Handle
      }
    });
  }

  public static void setStreamActive(final PlexMedia media, final Stream stream, final Runnable onFinish) {
    media.server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        Call<PlexResponse> call = null;
        if(stream.streamType == Stream.SUBTITLE) {
          call = service.setSubtitleStreamActive(stream.partId, stream.id, media.server.accessToken);
        } else if(stream.streamType == Stream.AUDIO) {
          call = service.setAudioStreamActive(stream.partId, stream.id, media.server.accessToken);
        }
        if(call != null) {
          call.enqueue(new Callback<PlexResponse>() {
            @Override
            public void onResponse(Response<PlexResponse> response, Retrofit retrofit) {
              Logger.d("set stream done");
              if(onFinish != null && response.body().code == 200)
                onFinish.run();
            }

            @Override
            public void onFailure(Throwable t) {

            }
          });
        }

      }

      @Override
      public void onFailure(int statusCode) {

      }
    });
  }

  public static void getChildren(final PlexDirectory directory, final PlexServer server, final PlexHttpMediaContainerHandler handler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        Call<MediaContainer> call = service.getChildren(directory.ratingKey, server.accessToken);
        call.enqueue(new Callback<MediaContainer>() {
          @Override
          public void onResponse(Response<MediaContainer> response, Retrofit retrofit) {
            MediaContainer mc = response.body();
            if(handler != null)
              handler.onSuccess(mc);
          }

          @Override
          public void onFailure(Throwable t) {
            if(handler != null)
              handler.onFailure(t);
          }
        });
      }

      @Override
      public void onFailure(int statusCode) {
        if(handler != null)
          handler.onFailure(new Throwable());
      }
    });
  }

  public static void getOnDeck(final PlexServer server, final String section, final PlexHttpMediaContainerHandler handler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        service.getOnDeck(section, server.accessToken)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(mc -> handler.onSuccess(mc), e -> handler.onFailure(new Throwable()));
      }

      @Override
      public void onFailure(int statusCode) {
        if(handler != null)
          handler.onFailure(new Throwable());
      }
    });
  }

  public static void getRecentlyAdded(final PlexServer server, final String section, final PlexHttpMediaContainerHandler handler) {
    server.findServerConnection(new ActiveConnectionHandler() {
      @Override
      public void onSuccess(Connection connection) {
        PlexHttpService service = getService(connection);
        service.getRecentlyAdded(section, server.accessToken)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(mc -> handler.onSuccess(mc), e -> handler.onFailure(new Throwable()));
      }

      @Override
      public void onFailure(int statusCode) {
        if(handler != null)
          handler.onFailure(new Throwable());
      }
    });
  }


}

