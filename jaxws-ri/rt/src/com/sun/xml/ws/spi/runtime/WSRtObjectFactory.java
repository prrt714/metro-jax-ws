/**
 * $Id: WSRtObjectFactory.java,v 1.7 2005-08-30 19:52:10 jitu Exp $
 *
 * Copyright 2005 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.sun.xml.ws.spi.runtime;

import java.io.OutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.sun.xml.ws.util.WSRtObjectFactoryImpl;
import java.net.URL;
import org.xml.sax.EntityResolver;

/**
 * Singleton abstract factory used to produce JAX-WS runtime related objects.
 */
public abstract class WSRtObjectFactory {

    private static final WSRtObjectFactory factory = new WSRtObjectFactoryImpl();

    /**
     * Obtain an instance of a factory. Don't worry about synchronization(at
     * the most, one more factory object is created).
     *
     */
    public static WSRtObjectFactory newInstance() {
        return factory;
    }

    /**
     * Creates SOAPMessageContext
     */
    public abstract SOAPMessageContext createSOAPMessageContext();


    /**
     * Creates an object with all endpoint info
     */
    public abstract RuntimeEndpointInfo createRuntimeEndpointInfo();
    
    /**
     * Creates a connection for servlet transport
     */
    public abstract WSConnection createWSConnection(
            HttpServletRequest req, HttpServletResponse res);


    /**
     * @param type The type of ClientTransportFactory
     * @see com.sun.xml.ws.spi.runtime.ClientTransportFactoryTypes
     */
    public abstract ClientTransportFactory createClientTransportFactory(
        int type,
        OutputStream logStream);

    
    /**
     * creates a Tie object, entry point to JAXWS runtime.
     */
    public abstract Tie createTie();
    
    /**
     * creates a MesageContext object. Create it for each MEP.
     */
    public abstract MessageContext createMessageContext();
    
    /**
     * creates the Binding object implementation. Set the object on
     * RuntimeEndpointInfo.
     * bindingId should be one of these values:
     * javax.xml.ws.soap.SOAPBinding.SOAP11HTTP_BINDING,
     * javax.xml.ws.soap.SOAPBinding.SOAP12HTTP_BINDING,
     * javax.xml.ws.http.HTTPBinding.HTTP_BINDING
     */
    public abstract Binding createBinding(String bindingId);
    
    /**
     * creates an EntityResolver for the XML Catalog URL
     */
    public abstract EntityResolver createEntityResolver(URL catalogUrl);

}
