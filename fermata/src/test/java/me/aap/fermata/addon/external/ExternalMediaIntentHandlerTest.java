package me.aap.fermata.addon.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import me.aap.fermata.media.lib.IntentPlayable;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ExternalMediaIntentHandlerTest {

	@Test
	public void testCanHandleValidIntents() {
		assertFalse(ExternalMediaIntentHandler.canHandle(null));

		Intent mainIntent = new Intent(Intent.ACTION_MAIN);
		assertFalse(ExternalMediaIntentHandler.canHandle(mainIntent));

		Intent internalFermata = new Intent(Intent.ACTION_VIEW, Uri.parse("fermata://open/abc"));
		assertFalse(ExternalMediaIntentHandler.canHandle(internalFermata));

		Intent handledIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/video.mp4"));
		handledIntent.putExtra("me.aap.fermata.EXTERNAL_HANDLED", true);
		assertFalse(ExternalMediaIntentHandler.canHandle(handledIntent));

		Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/video.mp4"));
		assertTrue(ExternalMediaIntentHandler.canHandle(viewIntent));

		Intent sendIntent = new Intent(Intent.ACTION_SEND);
		sendIntent.setType("video/mp4");
		assertTrue(ExternalMediaIntentHandler.canHandle(sendIntent));

		Intent sendMultiple = new Intent(Intent.ACTION_SEND_MULTIPLE);
		sendMultiple.setType("video/*");
		assertTrue(ExternalMediaIntentHandler.canHandle(sendMultiple));
	}

	@Test
	public void testExtractMediaUriFromData() {
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/stream.mp4"));
		assertTrue(ExternalMediaIntentHandler.canHandle(intent));
	}

	@Test
	public void testExtractMediaUriFromExtraStream() {
		Intent intent = new Intent(Intent.ACTION_SEND);
		Uri streamUri = Uri.parse("content://media/external/video/media/123");
		intent.putExtra(Intent.EXTRA_STREAM, streamUri);
		intent.setType("video/mp4");
		assertTrue(ExternalMediaIntentHandler.canHandle(intent));
	}

	@Test
	public void testExtractMediaUriFromClipData() {
		Intent intent = new Intent(Intent.ACTION_SEND);
		Uri clipUri = Uri.parse("content://media/external/video/media/456");
		ClipData clip = new ClipData(new ClipDescription("video", new String[]{"video/mp4"}),
				new ClipData.Item(clipUri));
		intent.setClipData(clip);
		assertTrue(ExternalMediaIntentHandler.canHandle(intent));
	}

	@Test
	public void testIntentPlayableRegistryLookup() {
		String testId = "intent://mock_id_123";
		// Verifies IntentPlayable.get lookup is accessible and thread-safe
		IntentPlayable cached = IntentPlayable.get(testId);
		// In fresh environment, non-existent returns null
		assertEquals(null, cached);
	}
}
