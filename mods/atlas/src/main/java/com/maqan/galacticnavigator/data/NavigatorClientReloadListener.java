package com.maqan.galacticnavigator.data;

/**
 * Intentionally no client-resource reload hook.
 *
 * Cosmic Horizons galaxy JSON files are server datapack resources. The live catalogue
 * is now rebuilt by NavigatorServerReloadListener from AddReloadListenerEvent; scanning
 * the client resource manager here would replace valid server data with an empty catalogue.
 */
@Deprecated
public final class NavigatorClientReloadListener {
    private NavigatorClientReloadListener() {}
}
