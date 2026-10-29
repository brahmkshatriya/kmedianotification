#include "kmedia_notification.h"
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>

@interface KMediaBox : NSObject
@property KMediaCommand callback;
@property void *user;
@property(nonatomic, strong) NSMutableArray *registrations;
@property BOOL closed;
@property BOOL liked;
@property(nonatomic, strong) NSString *artworkURL;
@property(nonatomic, strong) MPMediaItemArtwork *artwork;
@property(nonatomic, strong) NSURLSessionDataTask *artworkTask;
@end
@implementation KMediaBox
@end
static __weak KMediaBox *active_owner;
static void on_main(dispatch_block_t action) {
    if ([NSThread isMainThread]) action(); else dispatch_sync(dispatch_get_main_queue(), action);
}
void *kmedia_create(KMediaCommand callback, void *user, const char *id,
    const char *display_name, const char *desktop_entry, void *window, double minimum_rate, double maximum_rate) {
    KMediaBox *box = [KMediaBox new]; box.callback = callback; box.user = user;
    box.registrations = [NSMutableArray new];
    __block BOOL available = YES;
    on_main(^{
        if (active_owner) { available = NO; return; }
        active_owner = box;
        MPRemoteCommandCenter *c = [MPRemoteCommandCenter sharedCommandCenter];
        NSArray *commands = @[c.playCommand, c.pauseCommand, c.togglePlayPauseCommand,
            c.nextTrackCommand, c.previousTrackCommand, c.stopCommand, c.changePlaybackPositionCommand,
            c.skipForwardCommand, c.skipBackwardCommand, c.likeCommand,
            c.changeRepeatModeCommand, c.changeShuffleModeCommand, c.changePlaybackRateCommand];
        for (NSUInteger i = 0; i < commands.count; i++) {
            MPRemoteCommand *command = commands[i];
            NSObject *token = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                if (box.closed) return MPRemoteCommandHandlerStatusCommandFailed;
                int code = (int)i + 1; double value = 0;
                if (i == 6) value = ((MPChangePlaybackPositionCommandEvent *)event).positionTime * 1000;
                if (i == 7 || i == 8) { code = 8; value = ((MPSkipIntervalCommandEvent *)event).interval * (i == 7 ? 1000 : -1000); }
                if (i == 9) { code = 15; value = !box.liked; }
                if (i == 10) {
                    code = 11; MPRepeatType type = ((MPChangeRepeatModeCommandEvent *)event).repeatType;
                    value = type == MPRepeatTypeAll ? 1 : type == MPRepeatTypeOne ? 2 : 0;
                }
                if (i == 11) { code = 10; value = ((MPChangeShuffleModeCommandEvent *)event).shuffleType != MPShuffleTypeOff; }
                if (i == 12) { code = 12; value = ((MPChangePlaybackRateCommandEvent *)event).playbackRate; }
                return box.callback(box.user, code, value, NULL) ? MPRemoteCommandHandlerStatusSuccess : MPRemoteCommandHandlerStatusCommandFailed;
            }];
            [box.registrations addObject:@[command, token]];
        }
    });
    return available ? (__bridge_retained void *)box : NULL;
}
void kmedia_update(void *handle, const char *id, const char *title, const char *artist,
    const char *album, const char *artwork, int present, int playing, int actions, int kind,
    double position, double duration, double speed, double volume, int shuffle, int repeat, int liked, int discontinuity) {
    KMediaBox *box = (__bridge KMediaBox *)handle;
    NSString *track = [NSString stringWithUTF8String:id], *name = [NSString stringWithUTF8String:title], *performer = [NSString stringWithUTF8String:artist], *record = [NSString stringWithUTF8String:album], *cover = [NSString stringWithUTF8String:artwork];
    on_main(^{
        if (box.closed) return;
        MPRemoteCommandCenter *c = [MPRemoteCommandCenter sharedCommandCenter];
        c.playCommand.enabled = actions & (1 << 1); c.pauseCommand.enabled = actions & (1 << 2);
        c.togglePlayPauseCommand.enabled = actions & (1 << 3); c.nextTrackCommand.enabled = actions & (1 << 4);
        c.previousTrackCommand.enabled = actions & (1 << 5); c.stopCommand.enabled = actions & (1 << 6);
        c.changePlaybackPositionCommand.enabled = (actions & (1 << 7)) && duration > 0;
        c.skipForwardCommand.enabled = c.skipBackwardCommand.enabled = actions & (1 << 8);
        box.liked = liked == 1;
        c.likeCommand.enabled = present && liked >= 0 && (actions & (1 << 15));
        c.likeCommand.active = box.liked;
        c.likeCommand.localizedTitle = box.liked ? @"Unlike" : @"Like";
        c.changeRepeatModeCommand.enabled = actions & (1 << 11);
        c.changeRepeatModeCommand.currentRepeatType = repeat == 1 ? MPRepeatTypeAll : repeat == 2 ? MPRepeatTypeOne : MPRepeatTypeOff;
        c.changeShuffleModeCommand.enabled = actions & (1 << 10);
        c.changeShuffleModeCommand.currentShuffleType = shuffle ? MPShuffleTypeItems : MPShuffleTypeOff;
        c.changePlaybackRateCommand.enabled = actions & (1 << 12);
        if (!present) { [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil; return; }
        NSMutableDictionary *info = [@{MPMediaItemPropertyTitle:name, MPMediaItemPropertyArtist:performer,
            MPMediaItemPropertyAlbumTitle:record, MPNowPlayingInfoPropertyExternalContentIdentifier:track,
            MPNowPlayingInfoPropertyElapsedPlaybackTime:@(position / 1000),
            MPNowPlayingInfoPropertyMediaType:@(kind ? MPNowPlayingInfoMediaTypeVideo : MPNowPlayingInfoMediaTypeAudio),
            MPNowPlayingInfoPropertyPlaybackRate:@(playing ? speed : 0)} mutableCopy];
        if (duration >= 0) info[MPMediaItemPropertyPlaybackDuration] = @(duration / 1000);
        if (![cover isEqualToString:box.artworkURL]) {
            [box.artworkTask cancel]; box.artworkURL = cover; box.artwork = nil;
            NSURL *url = [NSURL URLWithString:cover];
            if (url.isFileURL) {
                NSImage *image = [[NSImage alloc] initWithContentsOfURL:url];
                if (image) box.artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size requestHandler:^NSImage *(CGSize size) { return image; }];
            } else if (url) box.artworkTask = [[NSURLSession sharedSession] dataTaskWithURL:url completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                NSImage *image = data ? [[NSImage alloc] initWithData:data] : nil;
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (box.closed || ![box.artworkURL isEqualToString:cover] || !image) return;
                    box.artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size requestHandler:^NSImage *(CGSize size) { return image; }];
                    NSMutableDictionary *current = [[MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo mutableCopy];
                    current[MPMediaItemPropertyArtwork] = box.artwork;
                    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = current;
                });
            }];
            [box.artworkTask resume];
        }
        if (box.artwork) info[MPMediaItemPropertyArtwork] = box.artwork;
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
        [MPNowPlayingInfoCenter defaultCenter].playbackState = playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
    });
}
void *kmedia_native_handle(void *handle) { return handle; }
void kmedia_destroy(void *handle) {
    KMediaBox *box = (__bridge_transfer KMediaBox *)handle;
    on_main(^{
        if (active_owner == box) active_owner = nil;
        box.closed = YES; [box.artworkTask cancel];
        for (NSArray *entry in box.registrations) {
            MPRemoteCommand *command = entry[0];
            [command removeTarget:entry[1]]; command.enabled = NO;
        }
        [box.registrations removeAllObjects]; [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
        [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
    });
}
