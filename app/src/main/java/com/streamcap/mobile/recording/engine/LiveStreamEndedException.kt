package com.streamcap.mobile.recording.engine

/** Only emitted after the platform explicitly confirms that the broadcaster is offline. */
class LiveStreamEndedException : Exception("主播已下播")
