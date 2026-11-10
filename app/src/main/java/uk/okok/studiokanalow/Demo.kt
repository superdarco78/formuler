package uk.okok.studiokanalow

import uk.okok.studiokanalow.model.Channel

/**
 * Tryb testowy (demo) - kilka publicznych strumieni testowych.
 * Nie laczy sie z serwerem klienta, nie zuzywa limitu dostawcy.
 * Sluzy tylko do sprawdzenia, czy aplikacja otwiera sie i odtwarza.
 */
object Demo {
    val channels: List<Channel> = listOf(
        Channel("d1", "Demo 1 — Big Buck Bunny", null, null, 1,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        Channel("d2", "Demo 2 — Tears of Steel", null, null, 2,
            "https://test-streams.mux.dev/pts_shift/master.m3u8"),
        Channel("d3", "Demo 3 — Apple BipBop", null, null, 3,
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"),
        Channel("d4", "Demo 4 — Sintel", null, null, 4,
            "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8")
    )

    val films: List<Channel> = listOf(
        Channel("f1", "Big Buck Bunny", null, null, 1,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        Channel("f2", "Tears of Steel", null, null, 2,
            "https://test-streams.mux.dev/pts_shift/master.m3u8"),
        Channel("f3", "Sintel", null, null, 3,
            "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8")
    )

    val seriale: List<Channel> = listOf(
        Channel("s1", "Serial demo — odc. 1 (Big Buck Bunny)", null, null, 1,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        Channel("s2", "Serial demo — odc. 2 (Sintel)", null, null, 2,
            "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
        Channel("s3", "Serial demo — odc. 3 (Tears of Steel)", null, null, 3,
            "https://test-streams.mux.dev/pts_shift/master.m3u8")
    )

    val radio: List<Channel> = listOf(
        Channel("r1", "Radio demo — Groove Salad", null, null, 1,
            "https://ice1.somafm.com/groovesalad-128-mp3"),
        Channel("r2", "Radio demo — Drone Zone", null, null, 2,
            "https://ice1.somafm.com/dronezone-128-mp3")
    )
}
