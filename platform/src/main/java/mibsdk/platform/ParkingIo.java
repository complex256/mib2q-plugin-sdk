package mibsdk.platform;

import mibsdk.api.SoundDirections;

/** Reads and writes the parking-sound configuration of the head unit. */
public interface ParkingIo {

    SoundDirections current();

    void apply(SoundDirections value) throws Exception;
}
