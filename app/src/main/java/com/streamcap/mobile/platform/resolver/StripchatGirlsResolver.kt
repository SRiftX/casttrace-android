package com.streamcap.mobile.platform.resolver

import okhttp3.OkHttpClient

/** Supports Stripchat model pages and the StripchatGirls domain through public page media URLs. */
class StripchatGirlsResolver(client: OkHttpClient) : PlatformResolver by GenericWebResolver(
    platformId = "stripchatgirls",
    hosts = listOf("stripchat.com", "stripchatgirls.com"),
    client = client,
)
