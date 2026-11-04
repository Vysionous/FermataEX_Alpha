package me.aap.fermata.media.lib;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.security.SecurityUtils.md5;

import android.net.Uri;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.resource.Rid;
import me.aap.utils.vfs.generic.GenericFileSystem;

/**
 * @author Andrey Pavlenko
 */
public class IntentPlayable extends PlayableItemBase {
	private static final Map<String, IntentPlayable> REGISTRY = new ConcurrentHashMap<>();
	private final boolean video;
	private final String displayName;

	public IntentPlayable(MainActivityDelegate a, Uri u) {
		this(a, u, null, null);
	}

	public IntentPlayable(MainActivityDelegate a, Uri u, @Nullable String mime, @Nullable String displayName) {
		super("intent://" + md5(u.toString()), new ExtRoot("intent_root", null) {
			@NonNull
			@Override
			public MediaLib getLib() {
				return a.getLib();
			}
		}, GenericFileSystem.getInstance().create(Rid.create(u)));
		this.displayName = displayName;
		if (mime == null) {
			try {
				mime = a.getContext().getContentResolver().getType(u);
			} catch (Exception ignored) {
			}
		}
		video = (mime == null) || mime.startsWith("video/");
		REGISTRY.put(getId(), this);
	}

	@Nullable
	public static IntentPlayable get(String id) {
		return REGISTRY.get(id);
	}

	@NonNull
	@Override
	public String getName() {
		return (displayName != null && !displayName.isEmpty()) ? displayName : super.getName();
	}

	@Override
	protected FutureSupplier<String> buildTitle(int seqNum, BrowsableItemPrefs parentPrefs) {
		if (displayName != null && !displayName.isEmpty()) {
			return completed(displayName);
		}
		return super.buildTitle(seqNum, parentPrefs);
	}

	@NonNull
	@Override
	public FutureSupplier<MediaDescriptionCompat> getMediaDescription() {
		return super.getMediaDescription();
	}

	@Override
	public boolean isVideo() {
		return video;
	}

	@Override
	public boolean isExternal() {
		return true;
	}

	@Override
	public boolean isSeekable() {
		return true;
	}

	@Override
	public String getOrigId() {
		return getId();
	}
}
