package opendct.sagetv;

import opendct.capture.CaptureDevice;
import opendct.capture.DCTCaptureDeviceImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.util.Comparator;

public class SageTVUnloadedDevice implements Comparable<SageTVUnloadedDevice> {
    private final Logger logger = LogManager.getLogger(SageTVUnloadedDevice.class);

    public final String ENCODER_NAME;
    private final Class<CaptureDevice> captureDeviceImpl;
    private final Object[] parameters;
    private final Class[] parameterTypes;
    private final boolean persist;

    public SageTVUnloadedDevice(String encoderName, Class captureDeviceImpl, Object parameters[], Class parameterTypes[], boolean persist) {
        ENCODER_NAME = encoderName;
        this.captureDeviceImpl = captureDeviceImpl;
        this.parameters = parameters;
        this.parameterTypes = parameterTypes;
        this.persist = persist;
    }

    /**
     * If this is set to true then this device is never removed from the unloaded devices. Use this
     * for devices that are entirely software and therefor unlimited.
     *
     * @return <i>true</i> if this device is to always be available in the unloaded devices.
     */
    public boolean isPersistent() {
        return persist;
    }

    // This needs to be able to handle the largest possible constructor to work correctly.
    public CaptureDevice getCaptureDevice() throws IllegalAccessException, InvocationTargetException, InstantiationException, NoSuchMethodException {

        if (!(parameters.length == parameterTypes.length)) {
            logger.error("The number of the parameter types and parameters don't match.");
            return null;
        }

        Constructor genericConstructor = captureDeviceImpl.getConstructor(parameterTypes);
        Object genericDevice = genericConstructor.newInstance(parameters);

        if (genericDevice instanceof CaptureDevice) {
            return (CaptureDevice) genericDevice;
        }

        logger.error("The object created '{}' was not a capture device.", genericDevice.getClass().getName());
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SageTVUnloadedDevice that = (SageTVUnloadedDevice) o;

        return ENCODER_NAME.equals(that.ENCODER_NAME);

    }

    @Override
    public int hashCode() {
        return ENCODER_NAME.hashCode();
    }

    @Override
    public int compareTo(SageTVUnloadedDevice o) {
        return -o.ENCODER_NAME.compareTo(ENCODER_NAME);
    }
}
