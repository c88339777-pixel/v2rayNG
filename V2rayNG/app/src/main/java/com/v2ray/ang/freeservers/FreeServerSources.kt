package com.v2ray.ang.freeservers

/**
 * Public GitHub lists of Hysteria2 and WireGuard links. Each one is fetched from
 * raw.githubusercontent.com first and from jsDelivr's mirror when GitHub is filtered.
 */
data class FreeServerSource(
    val repo: String,
    val branch: String,
    val path: String,
) {
    val rawUrl: String get() = "https://raw.githubusercontent.com/$repo/$branch/$path"
    val mirrorUrl: String get() = "https://cdn.jsdelivr.net/gh/$repo@$branch/$path"
}

object FreeServerSources {
    val ALL: List<FreeServerSource> = listOf(
        FreeServerSource("morpheusadam/v2ray-config", "main", "subs/bundles/hysteria2.txt"),
        FreeServerSource("morpheusadam/v2ray-config", "main", "subs/bundles/wireguard.txt"),
        FreeServerSource("rtwo2/FastNodes", "main", "sub/protocols/hysteria2.txt"),
        FreeServerSource("rtwo2/FastNodes", "main", "sub/protocols/hysteria2_part2.txt"),
        FreeServerSource("rtwo2/FastNodes", "main", "sub/protocols/hysteria2_part3.txt"),
        FreeServerSource("rtwo2/FastNodes", "main", "sub/protocols/hysteria2_part4.txt"),
        FreeServerSource("Argh73/VpnConfigCollector", "main", "Splitted-By-Protocol/Hysteria2.txt"),
        FreeServerSource("Argh73/VpnConfigCollector", "main", "Splitted-By-Protocol/WireGuard.txt"),
        FreeServerSource("Argh94/Proxy-List", "main", "Hysteria2.txt"),
        FreeServerSource("Argh94/Proxy-List", "main", "WireGuard.txt"),
        FreeServerSource("Argh94/V2RayAutoConfig", "main", "configs/Hysteria2.txt"),
        FreeServerSource("Argh94/V2RayAutoConfig", "main", "configs/WireGuard.txt"),
        FreeServerSource("10ium/VpnClashFaCollector", "main", "sub/all/hysteria2.txt"),
        FreeServerSource("10ium/VpnClashFaCollector", "main", "sub/all/wireguard.txt"),
        FreeServerSource("10Dream/sub-mod", "main", "sub/split/protocols/hy2.txt"),
        FreeServerSource("10Dream/sub-mod", "main", "sub/split/protocols/wireguard.txt"),
        FreeServerSource("Delta-Kronecker/WARP-Config", "main", "ALL.txt"),

        // VLESS / VMess / Trojan / Shadowsocks (including Outline-style ss:// keys)
        FreeServerSource("V2RayRoot/V2RayConfig", "main", "Config/vless.txt"),
        FreeServerSource("4n0nymou3/multi-proxy-config-fetcher", "main", "configs/proxy_configs_tested.txt"),
        FreeServerSource("kingowow/Kingo-vpn", "main", "server/KingoVpn.txt"),
        FreeServerSource("hello-world-1989/cn-news", "main", "end-gfw-together"),
        FreeServerSource("sakha1370/OpenRay", "main", "output/kind/ss.txt"),
        FreeServerSource("F0rc3Run/F0rc3Run", "main", "splitted-by-protocol/shadowsocks.txt"),
        FreeServerSource("barry-far/V2ray-Config", "main", "Splitted-By-Protocol/ss.txt"),
        FreeServerSource("barry-far/V2ray-Config", "main", "Splitted-By-Protocol/vless.txt"),
        FreeServerSource("barry-far/V2ray-Config", "main", "Splitted-By-Protocol/trojan.txt"),
        FreeServerSource("Epodonios/v2ray-configs", "main", "Splitted-By-Protocol/ss.txt"),
        FreeServerSource("Epodonios/v2ray-configs", "main", "Splitted-By-Protocol/vless.txt"),
        FreeServerSource("ebrasha/free-v2ray-public-list", "main", "ss_configs.txt"),
        FreeServerSource("mahdibland/ShadowsocksAggregator", "master", "sub/splitted/ss.txt"),
    )
}
