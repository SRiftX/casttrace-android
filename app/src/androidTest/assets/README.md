# Preview fixture

`preview.ts` is a synthetic two-second 160x90/25fps color test pattern with a 440Hz tone, generated locally for media-index and snapshot tests. It contains no captured user recording.

Generation:

```shell
ffmpeg -f lavfi -i testsrc2=size=160x90:rate=25 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 -c:v libx264 -preset ultrafast -g 25 -c:a aac -f mpegts preview.ts
```

Tests copy it to isolated temporary directories. They exercise MP4 remuxing, seekable tracks, frozen snapshots, checkpoint changes and source retention; this fixture does not replace live-stream/network testing.
