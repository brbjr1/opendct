/*
 * Copyright 2016 The OpenDCT Authors. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright (c) 2010 Nicolas George
 * Copyright (c) 2011 Stefano Sabatini
 * Copyright (c) 2014 Andrey Utkin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package opendct.video.ffmpeg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.javacpp.*;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static opendct.video.ffmpeg.FFmpegUtil.*;
import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avfilter.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;

public class FFmpegTranscoder implements FFmpegStreamProcessor {
    private static final Logger logger = LogManager.getLogger(FFmpegTranscoder.class);

    private static boolean trancodeOnInterlace = true;
    private static ConcurrentHashMap<String, String> jobsMap = new ConcurrentHashMap<>();
    private static int transcodeLimit = 2;

    private boolean assumptiveInterlaceDetection = false;
    private boolean fastInterlaceDetection = false;
    private long lastDtsByStreamIndex[] = new long[0];
    private AtomicInteger encodedFrames[] = new AtomicInteger[0];
    private long startTime = 0;

    private FFmpegContext ctx = null;
    private boolean interlaced = false;
    private FilteringContext filter_ctx[];

    private class FilteringContext {
        private avfilter.AVFilterContext buffersink_ctx;
        private avfilter.AVFilterContext buffersrc_ctx;
        private avfilter.AVFilterGraph filter_graph;
    }

    public static synchronized boolean getTranscodePermission(String encoderName, String token) {
        String desiredEncoder = jobsMap.get(encoderName);

        // The capture device is asking for permission, but the transcode limit has been reached.
        // The stream can only be remuxed now.
        if (desiredEncoder == null && jobsMap.size() >= transcodeLimit) {
            return false;
        }

        // A capture device that already has permission has returned, but has a different token.
        // This is not the same transcode job; permission denied. This is to make sure that capture
        // devices are returning permissions and only doing one transcode job per authorization.
        if (desiredEncoder != null && !desiredEncoder.equals(token)) {
            return false;
        }

        jobsMap.put(encoderName, token);
        return true;
    }

    public static synchronized void returnTranscodePermission(String encoderName) {
        if (encoderName != null) {
            jobsMap.remove(encoderName);
        }
    }

    @Override
    public void initStreamOutput(FFmpegContext ctx, String outputFilename) throws FFmpegException, InterruptedException {
        int ret;
        this.ctx = ctx;

        logger.info("Initializing FFmpeg transcoder stream output.");

        if (ctx.isInterrupted()) {
            throw new InterruptedException(FFMPEG_INIT_INTERRUPTED);
        }

        ctx.dumpInputFormat();

        // Creates the output container and AVFormatContext.
        ctx.allocAvfContainerOutputContext(outputFilename);

        int numInputStreams = ctx.avfCtxInput.nb_streams();

        lastDtsByStreamIndex = new long[numInputStreams];
        Arrays.fill(lastDtsByStreamIndex, Integer.MIN_VALUE);

        ctx.streamMap = new int[numInputStreams];
        Arrays.fill(ctx.streamMap, NO_STREAM_IDX);

        ctx.encodeMap = new boolean[numInputStreams];
        Arrays.fill(ctx.encodeMap, false); // It should be this by default, but let's not assume.

        ctx.encoderCodecs = new AVCodec[numInputStreams];
        ctx.encoderDicts = new AVDictionary[numInputStreams];

        encodedFrames = new AtomicInteger[numInputStreams];

        for (int i = 0; i < encodedFrames.length; i++) {
            encodedFrames[i] = new AtomicInteger(0);
        }

        if (ctx.isInterrupted()) {
            throw new InterruptedException(FFMPEG_INIT_INTERRUPTED);
        }

        interlaced = ctx.encodeProfile.canInterlaceDetect() && fastDeinterlaceDetection();

        if (ctx.isInterrupted()) {
            throw new InterruptedException(FFMPEG_INIT_INTERRUPTED);
        }

        if (ctx.encodeProfile.canTranscodeVideo(interlaced, avcodec_get_name(ctx.videoCodecCtx.codec_id()).getString())) {
            ret = avcodec_open2(ctx.videoCodecCtx,
                    avcodec_find_decoder(ctx.videoCodecCtx.codec_id()), (PointerPointer<AVDictionary>) null);

            if (ret < 0) {
                throw new FFmpegException("Failed to open decoder for stream #" + ctx.preferredVideo, ret);
            }

            if ((ctx.videoStream = addTranscodeVideoStreamToContext(ctx, ctx.preferredVideo, ctx.encodeProfile)) == null) {

                // If transcoding is not possible, we will just copy it.
                logger.warn("Unable to set up transcoding. The stream will be copied.");
                if ((ctx.videoStream = addCopyStreamToContext(ctx.avfCtxOutput, ctx.videoCodecCtx)) == null) {
                    throw new FFmpegException("Could not find a video stream", -1);
                }
            } else {
                ctx.encodeMap[ctx.preferredVideo] = true;
            }
        } else {
            if ((ctx.videoStream = addCopyStreamToContext(ctx.avfCtxOutput, ctx.videoCodecCtx)) == null) {
                throw new FFmpegException("Could not find a video stream", -1);
            }
        }

        if ((ctx.audioStream = addCopyStreamToContext(ctx.avfCtxOutput, ctx.audioCodecCtx)) == null) {
            throw new FFmpegException("Could not find a audio stream", -1);
        }

        ctx.streamMap[ctx.preferredVideo] = ctx.videoStream.id();
        ctx.streamMap[ctx.preferredAudio] = ctx.audioStream.id();

        for (int i = 0; i < numInputStreams; ++i) {
            if (ctx.streamMap[i] != NO_STREAM_IDX) {
                continue;
            }

            AVCodecContext codecCtx = getCodecContext(ctx.avfCtxInput.streams(i));

            if (codecCtx != null) {
                AVStream avsOutput = addCopyStreamToContext(ctx.avfCtxOutput, codecCtx);

                if (avsOutput != null) {
                    ctx.streamMap[i] = avsOutput.id();
                }
            }
        }

        if (ctx.isInterrupted()) {
            throw new InterruptedException(FFMPEG_INIT_INTERRUPTED);
        }

        filter_ctx = new FilteringContext[numInputStreams];

        if ((ret = initFilters()) < 0) {
            throw new FFmpegException("initFilters: Unable to allocate filters.", ret);
        }

        ctx.dumpOutputFormat();

        ctx.allocIoOutputContext(ctx.FFMPEG_WRITER);

        if (ctx.isInterrupted()) {
            deallocFilterGraphs();
            throw new InterruptedException(FFMPEG_INIT_INTERRUPTED);
        }

        logger.debug("Writing header");

        ret = avformat_write_header(ctx.avfCtxOutput, (PointerPointer<avutil.AVDictionary>) null);
        if (ret < 0) {
            deallocFilterGraphs();
            throw new FFmpegException("Error while writing header to file '" + outputFilename + "'", ret);
        }

        logger.info("Initialized FFmpeg transcoder stream output.");
    }

    private void deallocFilterGraphs() {
        if (ctx == null || filter_ctx == null) {
            return;
        }

        // The filter graphs have been allocated, so we need to de-allocate it before returning
        // from an interrupt.
        int nbStreams = ctx.avfCtxInput.nb_streams();

        for (int i = 0; i < nbStreams; i++) {
            if (filter_ctx != null && filter_ctx[i].filter_graph != null)
                avfilter_graph_free(filter_ctx[i].filter_graph);
        }
    }

    private void logPacket(AVFormatContext fmt_ctx, AVPacket pkt, String tag) {
        if (logger.isTraceEnabled()) {
            AVRational tb = fmt_ctx.streams(pkt.stream_index()).time_base();

            logger.trace(String.format("%s: pts:%s pts_time:%s dts:%s dts_time:%s duration:%s duration_time:%s stream_index:%d",
                    tag,
                    av_ts2str(pkt.pts()), av_ts2timestr(pkt.pts(), tb),
                    av_ts2str(pkt.dts()), av_ts2timestr(pkt.dts(), tb),
                    av_ts2str(pkt.duration()), av_ts2timestr(pkt.duration(), tb),
                    pkt.stream_index()));
        }
    }

    private String av_ts2str(long pts) {
        return pts == AV_NOPTS_VALUE ? "NOPTS" : Long.toString(pts);
    }

    private String av_ts2timestr(long pts, AVRational tb) {
        return pts == AV_NOPTS_VALUE ? "NOPTS" : String.format("%.6g", av_q2d(tb) * pts);
    }

    private boolean fastDeinterlaceDetection() throws FFmpegException {

        int ret = avcodec_open2(ctx.videoCodecCtx,
                avcodec_find_decoder(ctx.videoCodecCtx.codec_id()), (PointerPointer<AVDictionary>) null);

        if (ret < 0) {
            throw new FFmpegException("Failed to open decoder for stream #" + ctx.preferredVideo, ret);
        }

        AVPacket packet = new AVPacket();
        packet.data(null);
        packet.size(0);
        int got_frame[] = new int[] { 0 };
        AVFrame frame;

        // This is is the absolute frame limit. Once this number is reached the method will return
        // that this is not interlaced content.
        int absFrameLimit = 80;
        // This number will increase as interlaced flags are found. If no frames are found after 60
        // frames, we give up.
        int frameLimit = 60;
        int totalFrames = 0;
        int interThresh = 15;
        int interFrames = 0;

        try {
            if (ctx.SEEK_BUFFER != null) {
                ctx.SEEK_BUFFER.setNoWrap(true);
            }

            while(!ctx.isInterrupted()) {
                ret = av_read_frame(ctx.avfCtxInput, packet);
                if (ret < 0) {
                    if (ret != AVERROR_EOF) {
                        logger.error("Error reading frame during interlaced detection: {}", ret);
                    }
                    break;
                }

                int inputStreamIndex = packet.stream_index();

                AVStream stream = ctx.avfCtxInput.streams(inputStreamIndex);

                if (inputStreamIndex >= ctx.streamMap.length ||
                        inputStreamIndex != ctx.preferredVideo) {

                    // The packet is diverted to a queue to be processed after detection. If it is
                    // determined that re-muxing is preferred over transcoding, these packets will
                    // be fed to the re-muxer. If re-muxing isn't preferred, this queue can be
                    // de-allocated later.
                    av_packet_unref(packet);
                    continue;
                }

                frame = av_frame_alloc();
                if (frame == null) {
                    throw new FFmpegException("av_frame_alloc: Unable to allocate frame.", -1);
                }

                av_packet_rescale_ts(packet,
                        stream.time_base(),
                        stream.codec().time_base());

                ret = avcodec_decode_video2(stream.codec(), frame,
                        got_frame, packet);

                av_packet_rescale_ts(packet,
                        stream.codec().time_base(),
                        stream.time_base());

                if (ret < 0) {
                    av_frame_free(frame);
                    //av_packet_unref(packet);
                    logger.error("Decoding failed");
                    continue;
                }

                if (got_frame[0] != 0) {
                    int interlaced = frame.interlaced_frame();;
                    interFrames += interlaced;
                    frameLimit += interlaced;

                    // Do not retain decoded packets. The RAM usage will get insane very quickly.
                }

                av_frame_free(frame);
                av_packet_unref(packet);

                if (interFrames >= interThresh) {
                    return true;
                } else if (totalFrames++ >= frameLimit || totalFrames >= absFrameLimit) {
                    break;
                }
            }
        } catch (FFmpegException e) {
            logger.error("Deinterlace detection exception => ", e);
        } finally {
            avcodec_close(ctx.videoCodecCtx);
            //avio_seek(ctx.avfCtxInput.pb(), 0, 0);
            if (ctx.SEEK_BUFFER != null) {
                ctx.SEEK_BUFFER.setNoWrap(false);
            }
        }

        return false;
    }

    @Override
    public void streamOutput() throws FFmpegException {
        int ret = 0;

        AVPacket packet = new AVPacket();
        packet.data(null);
        packet.size(0);

        AVStream iavStream;
        AVCodecContext iavCodecContext;
        int inputStreamIndex;
        int type;
        int got_frame[] = new int[] { 0 };

        // This needs to start out null or Java complains about the cleanup.
        AVFrame frame = null;

        try {
            startTime = System.currentTimeMillis();

            while (true) {

                ret = av_read_frame(ctx.avfCtxInput, packet);
                if (ret < 0) {
                    break;
                }

                inputStreamIndex = packet.stream_index();
                int outputStreamIndex;

                if (inputStreamIndex >= ctx.streamMap.length ||
                        (outputStreamIndex = ctx.streamMap[inputStreamIndex]) == NO_STREAM_IDX) {

                    av_packet_unref(packet);
                    continue;
                }

                // Some streams will provide a dts value that's <= to the last value. Sometimes they
                // don't even have a dts value. These frames are discarded since they are normally
                // redundant.
                long dts = packet.dts();

                if (dts == AV_NOPTS_VALUE || lastDtsByStreamIndex[outputStreamIndex] >= dts) {
                    av_packet_unref(packet);
                    continue;
                } else {
                    lastDtsByStreamIndex[outputStreamIndex] = dts;
                }

                iavStream = ctx.avfCtxInput.streams(inputStreamIndex);
                iavCodecContext = iavStream.codec();
                type = iavStream.codec().codec_type();

                logger.trace("Demuxer gave frame of streamIndex {}",
                        inputStreamIndex);

                if (filter_ctx[inputStreamIndex].filter_graph != null) {

                    logger.trace("Going to re-encode & filter the frame");

                    frame = av_frame_alloc();
                    if (frame == null) {
                        throw new FFmpegException("av_frame_alloc: Unable to allocate frame.", ENOMEM);
                    }

                    logPacket(ctx.avfCtxInput, packet, "trans-dec-in");

                    av_packet_rescale_ts(packet,
                            iavStream.time_base(),
                            iavCodecContext.time_base());

                    logPacket(ctx.avfCtxInput, packet, "trans-dec-out");

                    if (type == AVMEDIA_TYPE_VIDEO) {
                        ret = avcodec_decode_video2(ctx.avfCtxInput.streams(inputStreamIndex).codec(), frame,
                                got_frame, packet);
                    } else {
                        ret = avcodec_decode_audio4(ctx.avfCtxInput.streams(inputStreamIndex).codec(), frame,
                                got_frame, packet);
                    }

                    if (ret < 0) {
                        av_frame_free(frame);
                        av_packet_unref(packet);
                        logger.error("Decoding failed");
                        continue;
                    }

                    if (got_frame[0] != 0) {
                        frame.pts(av_frame_get_best_effort_timestamp(frame));
                        ret = filterEncodeWriteFrame(frame, inputStreamIndex);
                        av_frame_free(frame);

                        if (ret < 0) {
                            logger.error("Error from filterEncodeWriteFrame: {}", ret);
                            //throw new FFmpegException("Error from filterEncodeWriteFrame.", ret);
                        }
                    } else {
                        av_frame_free(frame);
                    }
                } else {

                    logPacket(ctx.avfCtxInput, packet, "copy-in");

                    // remux this frame without re-encoding
                    av_packet_rescale_ts(packet,
                            ctx.avfCtxInput.streams(inputStreamIndex).time_base(),
                            ctx.avfCtxOutput.streams(outputStreamIndex).time_base());

                    logPacket(ctx.avfCtxInput, packet, "copy-out");

                    packet.stream_index(ctx.streamMap[inputStreamIndex]);

                    ret = av_interleaved_write_frame(ctx.avfCtxOutput, packet);

                    if (ret < 0) {
                        logger.error("Error from av_interleaved_write_frame: {}", ret);
                    }
                }

                av_packet_unref(packet);
            }

            int nbStreams = ctx.avfCtxInput.nb_streams();

            // flush filters and encoders
            for (int i = 0; i < nbStreams; i++) {

                // flush filter
                if (filter_ctx[i].filter_graph == null)
                    continue;

                ret = filterEncodeWriteFrame(null, i);

                if (ret < 0) {
                    throw new FFmpegException("Flushing filter failed", ret);
                }

            /* flush encoder */
                ret = flushEncoder(i);
                if (ret < 0) {
                    throw new FFmpegException("Flushing encoder failed", ret);
                }
            }

            av_write_trailer(ctx.avfCtxOutput);
        } finally {
            // Cleanup.
            endStreamOutput(packet, frame);
            logger.info("FFmpeg transcoder ended with code {}", ret);
        }
    }


    private void endStreamOutput(AVPacket packet, AVFrame frame) {
        av_packet_unref(packet);
        av_frame_free(frame);

        int nbStreams = ctx.avfCtxInput.nb_streams();

        for (int i = 0; i < nbStreams; i++) {
            avcodec_close(ctx.avfCtxInput.streams(i).codec());


            if (ctx.streamMap[i] != NO_STREAM_IDX && ctx.avfCtxOutput != null &&
                    ctx.avfCtxOutput.nb_streams() > i &&
                    ctx.avfCtxOutput.streams(ctx.streamMap[i]) != null &&
                    ctx.avfCtxOutput.streams(ctx.streamMap[i]).codec() != null) {
                avcodec_close(ctx.avfCtxOutput.streams(ctx.streamMap[i]).codec());
            }

            if (filter_ctx != null && filter_ctx[i].filter_graph != null)
                avfilter_graph_free(filter_ctx[i].filter_graph);

        }

        avformat_close_input(ctx.avfCtxInput);
        ctx.avfCtxInput = null;
        ctx.avioCtxInput = null;

        if (ctx.avfCtxOutput != null && (ctx.avfCtxOutput.oformat().flags() & AVFMT_NOFILE) != 0)
            avio_closep(ctx.avfCtxOutput.pb());
        avformat_free_context(ctx.avfCtxOutput);
        ctx.avfCtxOutput = null;
        ctx.avioCtxOutput = null;

    }

    private int initFilter(FilteringContext fctx, avcodec.AVCodecContext dec_ctx,
                           avcodec.AVCodecContext enc_ctx, final String filter_spec,
                           AVCodec encoder, AVDictionary dict) throws FFmpegException {

        int ret = 0;
        int decCodecType;
        avfilter.AVFilter buffersrc;
        avfilter.AVFilter buffersink;
        avfilter.AVFilterContext buffersrc_ctx;
        avfilter.AVFilterContext buffersink_ctx;
        avfilter.AVFilterInOut outputs = avfilter_inout_alloc();
        avfilter.AVFilterInOut inputs = avfilter_inout_alloc();
        avfilter.AVFilterGraph filter_graph = avfilter_graph_alloc();

        try {
            decCodecType = dec_ctx.codec_type();

            if (outputs == null || inputs == null || filter_graph == null) {
                throw new FFmpegException("Not enough memory available", ENOMEM);
            }

            if (decCodecType == AVMEDIA_TYPE_VIDEO) {
                buffersrc = avfilter_get_by_name("buffer");
                buffersink = avfilter_get_by_name("buffersink");
                if (buffersrc == null || buffersink == null) {
                    throw new FFmpegException("Filtering source or sink element not found",
                            AVERROR_UNKNOWN);
                }

                String parameters = String.format(
                        "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:frame_rate=%d/%d:pixel_aspect=%d/%d",
                        dec_ctx.width(), dec_ctx.height(), dec_ctx.pix_fmt(),
                        dec_ctx.time_base().num(), dec_ctx.time_base().den(),
                        dec_ctx.framerate().num(), dec_ctx.framerate().den(),
                        dec_ctx.sample_aspect_ratio().num(), dec_ctx.sample_aspect_ratio().den());

                /*String parameters = String.format(
                        "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d",
                        dec_ctx.width(), dec_ctx.height(), dec_ctx.pix_fmt(),
                        dec_ctx.time_base().num(), dec_ctx.time_base().den(),
                        dec_ctx.sample_aspect_ratio().num(), dec_ctx.sample_aspect_ratio().den());*/

                ret = avfilter_graph_create_filter(buffersrc_ctx = new AVFilterContext(null),
                        buffersrc, "in", parameters, null, filter_graph);
                if (ret < 0) {
                    throw new FFmpegException("Cannot create buffer source", ret);
                }

                ret = avfilter_graph_create_filter(buffersink_ctx = new AVFilterContext(null),
                        buffersink, "out", null, null, filter_graph);
                if (ret < 0) {
                    throw new FFmpegException("Cannot create buffer sink", ret);
                }

                BytePointer setBin = new BytePointer(4);
                setBin.asByteBuffer().putInt(enc_ctx.pix_fmt());
                ret = av_opt_set_bin(buffersink_ctx, "pix_fmts", setBin, 4, AV_OPT_SEARCH_CHILDREN);

                if (ret < 0) {
                    throw new FFmpegException("Cannot set pixel format", ret);
                }

            } else if (decCodecType == AVMEDIA_TYPE_AUDIO) {
                buffersrc = avfilter_get_by_name("abuffer");
                buffersink = avfilter_get_by_name("abuffersink");
                if (buffersrc == null || buffersink == null) {
                    throw new FFmpegException("filtering source or sink element not found", AVERROR_UNKNOWN);
                }

                if (dec_ctx.channel_layout() == 0) {
                    dec_ctx.channel_layout(av_get_default_channel_layout(dec_ctx.channels()));
                }

                String parameters = String.format(
                        "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=0x%x",
                        dec_ctx.time_base().num(), dec_ctx.time_base().den(), dec_ctx.sample_rate(),
                        av_get_sample_fmt_name(dec_ctx.sample_fmt()).getString(),
                        dec_ctx.channel_layout());

                ret = avfilter_graph_create_filter(buffersrc_ctx = new AVFilterContext(), buffersrc, "in",
                        parameters, null, filter_graph);
                if (ret < 0) {
                    throw new FFmpegException("Cannot create audio buffer source", ret);
                }

                ret = avfilter_graph_create_filter(buffersink_ctx = new AVFilterContext(), buffersink, "out",
                        null, null, filter_graph);
                if (ret < 0) {
                    throw new FFmpegException("Cannot create audio buffer sink", ret);
                }

                BytePointer setBin = new BytePointer(4);
                setBin.asByteBuffer().putInt(enc_ctx.sample_fmt());
                av_opt_set_bin(buffersink_ctx, "sample_fmts", setBin, 4, AV_OPT_SEARCH_CHILDREN);

                if (ret < 0) {
                    throw new FFmpegException("Cannot set output sample format", ret);
                }

                setBin = new BytePointer(8);
                setBin.asByteBuffer().putLong(enc_ctx.channel_layout());
                av_opt_set_bin(buffersink_ctx, "channel_layouts", setBin, 8, AV_OPT_SEARCH_CHILDREN);

                if (ret < 0) {
                    throw new FFmpegException("Cannot set output channel layout", ret);
                }

                setBin = new BytePointer(4);
                setBin.asByteBuffer().putInt(enc_ctx.sample_rate());
                av_opt_set_bin(buffersink_ctx, "sample_rates", setBin, 4, AV_OPT_SEARCH_CHILDREN);

                if (ret < 0) {
                    throw new FFmpegException("Cannot set output sample rate", ret);
                }

            } else {
                throw new FFmpegException("initFilter: Not audio or video.", AVERROR_UNKNOWN);
            }

            // Endpoints for the filter graph.
            outputs.name(av_strdup(new BytePointer("in")));
            outputs.filter_ctx(buffersrc_ctx);
            outputs.pad_idx(0);
            outputs.next(null);

            inputs.name(av_strdup(new BytePointer("out")));
            inputs.filter_ctx(buffersink_ctx);
            inputs.pad_idx(0);
            inputs.next(null);

            if (outputs.name() == null || inputs.name() == null) {
                throw new FFmpegException("av_strdup: Not enough memory.", ENOMEM);
            }

            ret = avfilter_graph_parse_ptr(filter_graph, filter_spec,
                    inputs, outputs, null);

            if (ret < 0) {
                throw new FFmpegException("avfilter_graph_parse_ptr: Unable to create.", ret);
            }

            ret = avfilter_graph_config(filter_graph, null);

            if (ret < 0) {
                throw new FFmpegException("avfilter_graph_config: Unable to create.", ret);
            }

            /* Fill FilteringContext */
            fctx.buffersrc_ctx = buffersrc_ctx;
            fctx.buffersink_ctx = buffersink_ctx;
            fctx.filter_graph = filter_graph;


            AVFilterContext outFilterContext = avfilter_graph_get_filter(filter_graph, "out");

            if (outFilterContext == null) {
                throw new FFmpegException("avfilter_graph_get_filter: Unable to get 'out' filter.", AVERROR_UNKNOWN);
            }

            int outFilterInputs = outFilterContext.nb_inputs();

            if (outFilterInputs == 1) {
                if (decCodecType == AVMEDIA_TYPE_VIDEO) {
                    int height;
                    int width;
                    int format;
                    AVRational ar;
                    AVRational fr;
                    AVRational tb;

                    if (logger.isDebugEnabled()) {
                        height = enc_ctx.height();
                        width = enc_ctx.width();
                        format = enc_ctx.pix_fmt();
                        ar = enc_ctx.sample_aspect_ratio();
                        fr = enc_ctx.framerate();
                        tb = enc_ctx.time_base();
                        //tb = rational;

                        logger.debug("Before filter: h:{} w:{} fmt:{} ar:{}/{} fr:{}/{} tb:{}/{}",
                                height, width, format, ar.num(), ar.den(), fr.num(), fr.den(), tb.num(), tb.den());
                    }

                    AVFilterLink input = outFilterContext.inputs(0);
                    height = input.h();
                    width = input.w();
                    format = input.format();
                    ar = input.sample_aspect_ratio();
                    fr = input.frame_rate();
                    tb = input.time_base();

                    if (logger.isDebugEnabled()) {
                        logger.debug("After filter: h:{} w:{} fmt:{} ar:{}/{} fr:{}/{} tb: {}/{}",
                                height, width, format, ar.num(), ar.den(), fr.num(), fr.den(), tb.num(), tb.den());
                    }

                    enc_ctx.height(height);
                    enc_ctx.width(width);
                    enc_ctx.pix_fmt(format);
                    enc_ctx.sample_aspect_ratio(ar);
                    enc_ctx.framerate(fr);
                    enc_ctx.time_base(tb);

                    ret = avcodec_open2(enc_ctx, encoder, dict);
                    av_dict_free(dict);

                    if (ret < 0) {
                        logger.error("Cannot open video encoder. Error {}.", ret);
                    }
                }
            } else {
                throw new FFmpegException("nb_inputs: 'out' filter has " + outFilterInputs + " inputs.", AVERROR_UNKNOWN);
            }

        } catch (FFmpegException e) {
            if (filter_graph != null) {
                avfilter_graph_free(filter_graph);
            }
            throw e;
        } finally {
            avfilter_inout_free(inputs);
            avfilter_inout_free(outputs);
        }

        return ret;
    }

    private int initFilters() throws FFmpegException {
        String filter_spec;

        int ret;
        int codecType;

        int nbStreams = ctx.avfCtxInput.nb_streams();

        filter_ctx = new FilteringContext[nbStreams];

        for (int i = 0; i < nbStreams; i++) {

            filter_ctx[i] = new FilteringContext();

            codecType = ctx.avfCtxInput.streams(i).codec().codec_type();

            if ( !ctx.encodeMap[i] || !(
                    codecType == AVMEDIA_TYPE_AUDIO ||
                    codecType == AVMEDIA_TYPE_VIDEO)) {

                continue;
            }

            filter_ctx[i].buffersrc_ctx = new AVFilterContext();
            filter_ctx[i].buffersink_ctx = new AVFilterContext();
            filter_ctx[i].filter_graph = new AVFilterGraph();

            if (codecType == AVMEDIA_TYPE_VIDEO) {
                //filter_spec = "null"; /* passthrough (dummy) filter for video */
                //filter_spec = "idet, yadif=mode=2:deint=interlaced, scale=w='trunc(oh*a/16)*16':h='min(720\\,ih)':interl=0:flags=bilinear, format=pix_fmts=yuv420p";
                filter_spec = "yadif=mode=2, scale=w='trunc(oh*a/16)*16':h='min(640\\,ih)':interl=0:flags=bilinear, format=pix_fmts=yuv420p";
                //filter_spec = "yadif=mode=3, format=pix_fmts=yuv420p";
                //filter_spec = "kerndeint=map=1, format=pix_fmts=yuv420p, scale=w=1280:h=720, setpts=0.5*PTS";

                //filter_spec = "idet";
            } else {
                filter_spec = "anull"; /* passthrough (dummy) filter for audio */
            }

            ret = initFilter(filter_ctx[i], ctx.avfCtxInput.streams(i).codec(),
                    ctx.avfCtxOutput.streams(ctx.streamMap[i]).codec(), filter_spec,
                    ctx.encoderCodecs[i], ctx.encoderDicts[i]);

            if (ret != 0) {
                return ret;
            }
        }

        return 0;
    }

    private int encodeWriteFrame(AVFrame filt_frame, int stream_index, int got_frame[]) {
        int ret = 0;
        avcodec.AVPacket enc_pkt = new avcodec.AVPacket();

        if (got_frame == null || got_frame.length == 0) {
            logger.warn("got_frame will not be able to be used ByRef.");
            got_frame = new int[] { 0 };
        }

        logger.trace("Encoding frame");
    /* encode filtered frame */
        enc_pkt.data(null);
        enc_pkt.size(0);
        av_init_packet(enc_pkt);

        int codecType = ctx.avfCtxInput.streams(stream_index).codec().codec_type();
        AVStream oavStream = ctx.avfCtxOutput.streams(ctx.streamMap[stream_index]);
        AVCodecContext oavCodec = oavStream.codec();

        if (codecType == AVMEDIA_TYPE_VIDEO) {
            ret = avcodec_encode_video2(oavCodec, enc_pkt,
                    filt_frame, got_frame);
        } else if (codecType == AVMEDIA_TYPE_AUDIO) {
            ret = avcodec_encode_audio2(oavCodec, enc_pkt,
                    filt_frame, got_frame);
        }

        av_frame_free(filt_frame);

        if (ret < 0) {
            return ret;
        }

        if (got_frame[0] == 0) {
            return 0;
        }

        logPacket(ctx.avfCtxOutput, enc_pkt, "trans-enc-in");

        // prepare packet for muxing
        enc_pkt.stream_index(ctx.streamMap[stream_index]);
        av_packet_rescale_ts(enc_pkt,
                oavCodec.time_base(),
                oavStream.time_base());

        logPacket(ctx.avfCtxOutput, enc_pkt, "trans-enc-out");

        logger.trace("Muxing frame");

        // mux encoded frame
        ret = av_interleaved_write_frame(ctx.avfCtxOutput, enc_pkt);

        if (encodedFrames[stream_index].addAndGet(1) == 1000) {
            long endTime = System.currentTimeMillis();
            if (startTime != endTime) {
                logger.debug("FPS: {}", (double)encodedFrames[stream_index].get() / (double)((endTime - startTime) / 1000));
            }
            encodedFrames[stream_index].set(0);
            startTime = endTime;
        }
        return ret;
    }

    private int filterEncodeWriteFrame(AVFrame frame, int stream_index) {
        int ret;
        AVFrame filt_frame;
        int got_frame[] = new int[] { 0 };

        logger.trace("Pushing decoded frame to filters");
    /* push the decoded frame into the filtergraph */
        ret = av_buffersrc_add_frame_flags(filter_ctx[stream_index].buffersrc_ctx,
                frame, 0);
        if (ret < 0) {
            logger.error("Error while feeding the filtergraph");
            return ret;
        }

    /* pull filtered frames from the filtergraph */
        while (true) {
            filt_frame = av_frame_alloc();

            if (filt_frame == null) {
                ret = ENOMEM;
                break;
            }

            logger.trace("Pulling filtered frame from filters");
            ret = av_buffersink_get_frame(filter_ctx[stream_index].buffersink_ctx,
                    filt_frame);

            if (ret < 0) {
            /* if no more frames for output - returns AVERROR(EAGAIN)
             * if flushed and no more frames for output - returns AVERROR_EOF
             * rewrite retcode to 0 to show it as normal procedure completion
             */
                if (ret == AVERROR_EOF || ret == EAGAIN) {
                    ret = 0;
                }

                av_frame_free(filt_frame);
                break;
            }

            filt_frame.pict_type(AV_PICTURE_TYPE_NONE);
            ret = encodeWriteFrame(filt_frame, stream_index, got_frame);

            if (ret < 0) {
                break;
            }
        }

        return ret;
    }

    private int flushEncoder(int stream_index) {
        if (ctx.streamMap[stream_index] == NO_STREAM_IDX) {
            return 0;
        }

        int ret;
        int got_frame[] = new int[] { 0 };

        if ((ctx.avfCtxOutput.streams(ctx.streamMap[stream_index]).codec().codec().capabilities() &
                AV_CODEC_CAP_DELAY) == 0) {

            return 0;
        }

        while (true) {
            logger.debug("Flushing stream #{} encoder", stream_index);
            ret = encodeWriteFrame(null, stream_index, got_frame);

            if (ret < 0) {
                break;
            }

            if (got_frame[0] == 0) {
                return 0;
            }
        }

        return ret;
    }
}