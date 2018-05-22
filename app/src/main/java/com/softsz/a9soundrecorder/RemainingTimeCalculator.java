/**
 * Calculates remaining recording time based on available disk space and
 * optionally a maximum recording file size.
 *
 * The reason why this is not trivial is that the file grows in blocks every few
 * seconds or so, while we want a smooth count down.
 */
package com.softsz.a9soundrecorder;

import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;
import java.io.File;

class RemainingTimeCalculator {
    private static final String TAG = "RemainingTimeCalculator";
    public static final int UNKNOWN_LIMIT = 0;
    public static final int FILE_SIZE_LIMIT = 1;
    public static final int DISK_SPACE_LIMIT = 2;
    /** M: static variable about magic number @{ */
    private static final int ONE_SECOND = 1000;
    private static final int BIT_RATE = 8;
    private static final float RESERVE_SAPCE = SeSoundRecorderService.LOW_STORAGE_THRESHOLD / 2;
    /** @} */
    private static final String SOUNDRECORD = "SoundRecord";

    // which of the two limits we will hit (or have fit) first
    private int mCurrentLowerLimit = UNKNOWN_LIMIT;
    // Rate at which the file grows
    private int mBytesPerSecond;

    /** M: using for calculating more accurate/normal remaining time @{ */
    // the last time run timeRemaining()
    private long mLastTimeRunTimeRemaining;
    // the last remaining time
    private long mLastRemainingTime = -1;
    /** @} */
    private long mMaxBytes;
    // time at which number of free blocks last changed
    private long mBlocksChangedTime;
    // number of available blocks at that time
    private long mLastBlocks;
    // time at which the size of the file has last changed
    private long mFileSizeChangedTime = -1;
    // size of the file at that time
    private long mLastFileSize;

    // State for tracking file size of recording.
    private File mRecordingFile;
    private String mSDCardDirectory;
    private final StorageManager mStorageManager;
    // if recording has been pause
    private boolean mPauseTimeRemaining = false;
    private SeSoundRecorderService mService;
    private String mFilePath;

    /**
     * the construction of RemainingTimeCalculator
     *
     * @param storageManager
     *            StorageManager
     */
    public RemainingTimeCalculator(StorageManager storageManager, SeSoundRecorderService service) {
        /** M: initialize mStorageManager */
        mStorageManager = storageManager;
        /** M: initialize mSDCardDirectory using a function */
        getSDCardDirectory();
        /** M: initialize mService */
        mService = service;
    }

    /**
     * If called, the calculator will return the minimum of two estimates: how
     * long until we run out of disk space and how long until the file reaches
     * the specified size.
     *
     * @param file
     *            the file to watch
     * @param maxBytes
     *            the limit
     */
    public void setFileSizeLimit(File file, long maxBytes) {
        mRecordingFile = file;
        mMaxBytes = maxBytes;
    }

    /**
     * Resets the interpolation.
     */
    public void reset() {
        Log.d(TAG,"<reset>");
        mCurrentLowerLimit = UNKNOWN_LIMIT;
        mBlocksChangedTime = -1;
        mFileSizeChangedTime = -1;
        /** M: reset new variable @{ */
        mPauseTimeRemaining = false;
        mLastRemainingTime = -1;
        mLastBlocks = -1;
        getSDCardDirectory();
        /** @} */
    }

    /**
     * M: return byte rate, using by SoundRecorder class when store state
     *
     * @return byt e rate
     */
    public int getByteRate() {
        return mBytesPerSecond;
    }

    /**
     * M: in order to calculate more accurate remaining time, set
     * mPauseTimeRemaining as true when MediaRecorder pause recording
     *
     * @param pause
     *            whether set mPauseTimeRemaining as true
     */
    public void setPauseTimeRemaining(boolean pause) {
        mPauseTimeRemaining = pause;
    }

    /**
     * M: Returns how long (in seconds) we can continue recording. Because the
     * remaining time is calculated by estimation, add man-made control to
     * remaining time, and make it not increase when available blocks is
     * reducing
     * @return the remaining time that Recorder can record
     */
    public long timeRemaining() {
        // Calculate how long we can record based on free disk space
        // LogUtils.i(TAG,"<timeRemaining> mBytesPerSecond = " +
        // mBytesPerSecond);
        boolean blocksNotChangeMore = false;
        StatFs fs = null;
        long blocks = 0;
        long blockSize = 0;
        try {
            fs = new StatFs(mSDCardDirectory);
            blocks = fs.getAvailableBlocks() - 1;
            blockSize = fs.getBlockSize();
        } catch (IllegalArgumentException e) {
            fs = null;
            Log.d(TAG, "stat " + mSDCardDirectory + " failed...");
            return SeSoundRecorderService.ERROR_PATH_NOT_EXIST;
        }
        long now = SystemClock.elapsedRealtime();
        if ((-1 == mBlocksChangedTime) || (blocks != mLastBlocks)) {
            // LogUtils.i(TAG, "<timeRemaining> blocks has changed from " +
            // mLastBlocks + " to "
            // + blocks);
            blocksNotChangeMore = (blocks <= mLastBlocks) ? true : false;
            // LogUtils.i(TAG, "<timeRemaining> blocksNotChangeMore = " +
            // blocksNotChangeMore);
            mBlocksChangedTime = now;
            mLastBlocks = blocks;
        } else if (blocks == mLastBlocks) {
            blocksNotChangeMore = true;
        }

        /*
         * The calculation below always leaves one free block, since free space
         * in the block we're currently writing to is not added. This last block
         * might get nibbled when we close and flush the file, but we won't run
         * out of disk.
         */

        // at mBlocksChangedTime we had this much time
        float resultTemp = ((float) (mLastBlocks * blockSize - RESERVE_SAPCE)) / mBytesPerSecond;

        // if recording has been pause, we should add pause time to
        // mBlocksChangedTime
        // LogUtils.i(TAG, "<timeRemaining> mPauseTimeRemaining = " +
        // mPauseTimeRemaining);
        if (mPauseTimeRemaining) {
            mBlocksChangedTime += (now - mLastTimeRunTimeRemaining);
            mPauseTimeRemaining = false;
        }
        mLastTimeRunTimeRemaining = now;

        // so now we have this much time
        resultTemp -= ((float) (now - mBlocksChangedTime)) / ONE_SECOND;
        long resultDiskSpace = (long) resultTemp;
        mLastRemainingTime = (-1 == mLastRemainingTime) ? resultDiskSpace : mLastRemainingTime;
        if (blocksNotChangeMore && (resultDiskSpace > mLastRemainingTime)) {
            // LogUtils.i(TAG, "<timeRemaining> result = " + resultDiskSpace
            // + " blocksNotChangeMore = true");
            resultDiskSpace = mLastRemainingTime;
            // LogUtils.i(TAG, "<timeRemaining> result = " + resultDiskSpace);
        } else {
            mLastRemainingTime = resultDiskSpace;
            // LogUtils.i(TAG, "<timeRemaining> result = " + resultDiskSpace);
        }

            mCurrentLowerLimit = DISK_SPACE_LIMIT;
            return resultDiskSpace;
    }

    /**
     * Indicates which limit we will hit (or have hit) first, by returning one
     * of FILE_SIZE_LIMIT or DISK_SPACE_LIMIT or UNKNOWN_LIMIT. We need this to
     * display the correct message to the user when we hit one of the limits.
     *
     * @return current limit is FILE_SIZE_LIMIT or DISK_SPACE_LIMIT
     */
    public int currentLowerLimit() {
        return mCurrentLowerLimit;
    }

    /**
     * Sets the bit rate used in the interpolation.
     *
     * @param bitRate
     *            the bit rate to set in bits/second.
     */
    public void setBitRate(int bitRate) {
        mBytesPerSecond = bitRate / BIT_RATE;
        Log.i(TAG, "<setBitRate> mBytesPerSecond = " + mBytesPerSecond);
    }

    /** M: define a function to initialize the SD Card Directory */
    private void getSDCardDirectory() {
        if (null != mStorageManager) {
            mSDCardDirectory = SeRecorder.STORAGE_PATH_SHARE_SD;
        }
    }

    /**
     * the remaining disk space that Record can record
     *
     * @return the remaining disk space
     */
    public long diskSpaceRemaining() {
        StatFs fs = new StatFs(mSDCardDirectory);
        long blocks = fs.getAvailableBlocks() - 1;
        long blockSize = fs.getBlockSize();
        return (long) ((blocks * blockSize) - RESERVE_SAPCE);
    }
}
