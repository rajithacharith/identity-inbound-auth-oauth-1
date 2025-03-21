package org.wso2.carbon.identity.oauth.dcr.exception;

import org.wso2.carbon.identity.oauth.dcr.DCRException;

/**
 * Exception class used to handle exceptions occur during registering an OAuth application.
 */
@Deprecated
public class RegistrationException extends DCRException {

    public RegistrationException(String message) {

        super(message);
    }

    public RegistrationException(String errorCode, String message) {

        super(errorCode, message);
    }

    public RegistrationException(String message, Throwable cause) {

        super(message, cause);
    }

    public RegistrationException(String errorCode, String message, Throwable cause) {

        super(errorCode, message, cause);
    }
}
