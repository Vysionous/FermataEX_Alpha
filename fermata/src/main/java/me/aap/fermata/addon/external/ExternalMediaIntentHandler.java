package me.aap.fermata.addon.external;

import static me.aap.utils.async.Completed.completed;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.auto.AutomotiveConnectionState;
import me.aap.fermata.auto.AutomotiveNavigationController;
import me.aap.fermata.auto.OpenOnCarMediaRouting;
import me.aap.fermata.media.lib.IntentPlayable;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.policy.RuntimeHostMode;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * Normalizes, validates, and dispatches external video playback requests.
 * Handles both cold-start and warm-start intents (ACTION_VIEW, ACTION_SEND).
 */
public final class ExternalMediaIntentHandler {
	private static final String EXTRA_HANDLED = "me.aap.fermata.EXTERNAL_HANDLED";
	private static String lastHandledUriString;
	private static String lastHandledAction;
	private static long lastHandledTime;

	private ExternalMediaIntentHandler() {
	}

	public static boolean canHandle(@Nullable Intent intent) {
		if (intent == null) return false;
		String action = intent.getAction();
		if (action == null) return false;
		if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return false;

		Uri data = intent.getData();
		if (data != null && "fermata".equalsIgnoreCase(data.getScheme())) {
			return false;
		}

		return Intent.ACTION_VIEW.equals(action)
				|| Intent.ACTION_SEND.equals(action)
				|| Intent.ACTION_SEND_MULTIPLE.equals(action);
	}

	public static FutureSupplier<Boolean> handle(@NonNull MainActivityDelegate activity,
			@NonNull Intent intent) {
		String action = intent.getAction();
		if (action == null) return completed(false);

		// Multiple items validation
		if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
			Log.w("ExternalMediaIntentHandler: Rejected ACTION_SEND_MULTIPLE request");
			showFailure(activity, R.string.external_play_err_multiple_items);
			intent.putExtra(EXTRA_HANDLED, true);
			return completed(true);
		}

		ClipData clipData = intent.getClipData();
		if (clipData != null && clipData.getItemCount() > 1 && Intent.ACTION_SEND.equals(action)) {
			Log.w("ExternalMediaIntentHandler: Rejected multiple items in ClipData for ACTION_SEND");
			showFailure(activity, R.string.external_play_err_multiple_items);
			intent.putExtra(EXTRA_HANDLED, true);
			return completed(true);
		}

		// Extract media URI
		Uri mediaUri = extractMediaUri(intent);
		if (mediaUri == null) {
			Log.w("ExternalMediaIntentHandler: No media URI found in intent ", action);
			showFailure(activity, R.string.external_play_err_missing_media);
			intent.putExtra(EXTRA_HANDLED, true);
			return completed(true);
		}

		// Duplicate intent check within 1500ms
		long now = System.currentTimeMillis();
		String uriStr = mediaUri.toString();
		if (uriStr.equals(lastHandledUriString) && action.equals(lastHandledAction) && (now - lastHandledTime < 1500)) {
			Log.d("ExternalMediaIntentHandler: Suppressed duplicate external intent within window");
			return completed(true);
		}
		lastHandledUriString = uriStr;
		lastHandledAction = action;
		lastHandledTime = now;
		intent.putExtra(EXTRA_HANDLED, true);

		// Scheme validation
		String scheme = mediaUri.getScheme();
		if (scheme == null) {
			Log.w("ExternalMediaIntentHandler: Media URI has null scheme");
			showFailure(activity, R.string.external_play_err_unsupported_format);
			return completed(true);
		}
		scheme = scheme.toLowerCase(Locale.ROOT);
		if (!scheme.equals("content") && !scheme.equals("file")
				&& !scheme.equals("http") && !scheme.equals("https")) {
			Log.w("ExternalMediaIntentHandler: Unsupported URI scheme: ", scheme);
			showFailure(activity, R.string.external_play_err_unsupported_format);
			return completed(true);
		}

		Context context = activity.getContext();

		// Content URI permissions and access check
		if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
			if ((intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
				try {
					context.getContentResolver().takePersistableUriPermission(
							mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
				} catch (SecurityException ex) {
					Log.d("ExternalMediaIntentHandler: Persistable grant not permitted: ", ex.getMessage());
				}
			}
			try {
				context.grantUriPermission(context.getPackageName(), mediaUri,
						Intent.FLAG_GRANT_READ_URI_PERMISSION);
			} catch (Exception ex) {
				Log.d("ExternalMediaIntentHandler: Granting URI permission to package failed: ", ex.getMessage());
			}

			// Validate readability via ContentResolver
			try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(mediaUri, "r")) {
				if (afd == null) {
					Log.w("ExternalMediaIntentHandler: ContentResolver returned null AssetFileDescriptor");
					showFailure(activity, R.string.external_play_err_inaccessible);
					return completed(true);
				}
			} catch (IOException | SecurityException | IllegalArgumentException ex) {
				Log.e(ex, "ExternalMediaIntentHandler: Failed to access content URI: ", mediaUri.getScheme());
				showFailure(activity, R.string.external_play_err_inaccessible);
				return completed(true);
			}
		}

		// Resolve MIME type and display name
		String mimeType = resolveMimeType(context, intent, mediaUri);
		if (isExplicitlyUnsupportedType(mimeType)) {
			Log.w("ExternalMediaIntentHandler: Unsupported MIME type: ", mimeType);
			showFailure(activity, R.string.external_play_err_unsupported_format);
			return completed(true);
		}

		String displayName = resolveDisplayName(context, mediaUri);
		Log.i("ExternalMediaIntentHandler: Validated external request. Scheme=", scheme,
				", mime=", mimeType, ", name=", displayName);

		// Build playable item
		IntentPlayable playable = new IntentPlayable(activity, mediaUri, mimeType, displayName);

		// Determine routing: Car vs Phone
		dispatchPlayback(activity, playable);
		return completed(true);
	}

	@Nullable
	private static Uri extractMediaUri(@NonNull Intent intent) {
		Uri uri = intent.getData();
		if (uri != null) return uri;

		if (Intent.ACTION_SEND.equals(intent.getAction())) {
			Parcelable stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
			if (stream instanceof Uri streamUri) return streamUri;
		}

		ClipData clipData = intent.getClipData();
		if (clipData != null && clipData.getItemCount() > 0) {
			ClipData.Item item = clipData.getItemAt(0);
			if (item != null && item.getUri() != null) return item.getUri();
		}

		return null;
	}

	@Nullable
	private static String resolveMimeType(@NonNull Context context, @NonNull Intent intent,
			@NonNull Uri uri) {
		String type = intent.getType();
		if (type != null && !type.isBlank() && !type.equals("*/*")) return type;

		if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
			try {
				String contentMime = context.getContentResolver().getType(uri);
				if (contentMime != null) return contentMime;
			} catch (Exception ignored) {
			}
		}

		String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
		if (extension != null && !extension.isBlank()) {
			String extMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
			if (extMime != null) return extMime;
		}

		return null;
	}

	private static boolean isExplicitlyUnsupportedType(@Nullable String mime) {
		if (mime == null) return false;
		String lower = mime.toLowerCase(Locale.ROOT);
		return lower.startsWith("image/") || lower.startsWith("text/")
				|| lower.equals("application/pdf") || lower.equals("application/zip");
	}

	@NonNull
	private static String resolveDisplayName(@NonNull Context context, @NonNull Uri uri) {
		if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
			try (Cursor cursor = context.getContentResolver().query(uri,
					new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
				if (cursor != null && cursor.moveToFirst()) {
					int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
					if (col >= 0) {
						String name = cursor.getString(col);
						if (name != null && !name.isBlank()) return name;
					}
				}
			} catch (Exception ignored) {
			}
		}

		String segment = uri.getLastPathSegment();
		if (segment != null && !segment.isBlank()) {
			int lastSlash = segment.lastIndexOf('/');
			return lastSlash >= 0 ? segment.substring(lastSlash + 1) : segment;
		}

		return "External Media";
	}

	private static void dispatchPlayback(@NonNull MainActivityDelegate activity,
			@NonNull IntentPlayable playable) {
		AutomotiveConnectionState conn = AutomotiveConnectionState.get();
		AutomotiveNavigationController nav = AutomotiveNavigationController.get();
		boolean projectionConnected = conn.isProjectionConnected();
		boolean openOnCarEnabled = nav.getOpenOnCarMode().isEnabled();
		boolean carReady = nav.getOpenOnCarMode().isAvailable();

		FermataServiceUiBinder binder = activity.getMediaServiceBinder();
		OpenOnCarMediaRouting.Selection selection = binder.captureUserSelection();

		if (selection.forwardToCar() && projectionConnected && carReady) {
			Log.i("ExternalMediaIntentHandler: Forwarding playback to car surface");
			showInfo(activity, R.string.external_play_dispatched_car);
			activity.getBody().playSelection(selection, playable, -1).onCompletion((res, err) -> {
				activity.post(() -> {
					if (err != null || res == AutomotiveNavigationController.OpenResult.FAILED
							|| res == AutomotiveNavigationController.OpenResult.NOT_READY) {
						Log.w("ExternalMediaIntentHandler: Car dispatch not ready; falling back to phone");
						showInfo(activity, R.string.external_play_car_not_ready_phone);
						activity.getBody().playRoutedItem(playable, -1,
								new OpenOnCarMediaRouting.Admission(RuntimeHostMode.PHONE, () -> true));
					}
				});
			});
		} else {
			if (openOnCarEnabled && (!projectionConnected || !carReady)) {
				showInfo(activity, R.string.external_play_car_unavailable_phone);
			}
			activity.getBody().playItem(playable, -1);
		}
	}

	private static void showFailure(@NonNull MainActivityDelegate activity, @StringRes int messageRes) {
		activity.post(() -> UiUtils.showAlert(activity.getContext(), messageRes));
	}

	private static void showInfo(@NonNull MainActivityDelegate activity, @StringRes int messageRes) {
		activity.post(() -> UiUtils.showInfo(activity.getContext(), messageRes));
	}
}
