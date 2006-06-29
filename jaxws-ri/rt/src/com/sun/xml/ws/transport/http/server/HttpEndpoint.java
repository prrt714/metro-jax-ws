/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at
 * https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2006 Sun Microsystems Inc. All Rights Reserved
 */

package com.sun.xml.ws.transport.http.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.xml.ws.server.DocInfo;
import com.sun.xml.ws.server.RuntimeEndpointInfo;
import com.sun.xml.ws.server.ServerRtException;
import com.sun.xml.ws.server.Tie;
import com.sun.xml.ws.spi.runtime.WebServiceContext;
import com.sun.xml.ws.util.ByteArrayBuffer;
import com.sun.xml.ws.util.xml.XmlUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import javax.xml.transform.stream.StreamSource;
import org.xml.sax.EntityResolver;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.ws.Endpoint;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import com.sun.xml.ws.spi.runtime.Binding;

/**
 *
 * @author WS Development Team
 */
public class HttpEndpoint {
    
    private static final Logger logger =
        Logger.getLogger(
            com.sun.xml.ws.util.Constants.LoggingDomain + ".server.http");
    
    private String address;
    private HttpContext httpContext;
    private final RuntimeEndpointInfo endpointInfo;
    private String primaryWsdl;
    private Executor executor;
    private final Map<String, Object> properties;
    
    private static final int MAX_THREADS = 5;
    
    public HttpEndpoint(Object implementor, Binding binding, List<Source>metadata, Map<String, Object> properties, Executor executor) {
        endpointInfo = new RuntimeEndpointInfo();
        endpointInfo.setImplementor(implementor);
        endpointInfo.setImplementorClass(implementor.getClass());
        endpointInfo.setBinding(binding);
        endpointInfo.setUrlPattern("");
        endpointInfo.setMetadata(metadata);
        this.properties = properties;
        this.executor = executor;
    }
    
    /**
     * If Service Name is in properties, set it on RuntimeEndpointInfo
     */
    private void setServiceName() {
        if (properties != null) {
            QName serviceName = (QName)properties.get(Endpoint.WSDL_SERVICE);
            if (serviceName != null) {
                endpointInfo.setServiceName(serviceName);
            }
        }
    }
    
    /**
     * If Port Name is in properties, set it on RuntimeEndpointInfo
     */
    private void setPortName() {
        if (properties != null) {
            QName portName = (QName)properties.get(Endpoint.WSDL_PORT);
            if (portName != null) {
                endpointInfo.setPortName(portName);
            }
        }
    }

    /**
     * Convert metadata sources using identity transform. So that we can
     * reuse the Source object multiple times.
     */
    private void setDocInfo() throws MalformedURLException {
        List<Source> metadata = endpointInfo.getMetadata();
        
        // Takes care of @WebService, @WebServiceProvider's wsdlLocation
        String wsdlLocation = endpointInfo.getWsdlLocation();
        if (wsdlLocation != null) {
            ClassLoader cl = endpointInfo.getImplementorClass().getClassLoader();
            URL url = cl.getResource(wsdlLocation);
            if (url == null) {
                throw new ServerRtException("cannot.load.wsdl", wsdlLocation);
            } else {
                Source source = null;
                try {
                    source = new StreamSource(url.openStream());
                } catch(IOException ioe) {
                    throw new ServerRtException("server.rt.err", ioe);
                }
                primaryWsdl = url.toExternalForm();
                source.setSystemId(primaryWsdl);
                if (metadata == null) {
                    metadata = new ArrayList<Source>();
                    endpointInfo.setMetadata(metadata);
                }
                metadata.add(source);
            }
        }
        
        // Creates DocInfo for each metdata Source
        if (metadata != null) {
            Map<String, DocInfo> newMetadata = new HashMap<String, DocInfo>();
            Transformer transformer = XmlUtil.newTransformer();
            for(Source source: metadata) {
                ByteArrayBuffer baos = new ByteArrayBuffer();
                try {
                    transformer.transform(source, new StreamResult(baos));
                    baos.close();
                } catch (TransformerException te) {
                    throw new ServerRtException("server.rt.err",te);
                }
                String systemId = source.getSystemId();
                URL url;
                try {
                    url = new URL(systemId);
                } catch(MalformedURLException me) {
                    logger.severe("Metadata Source's systemId="+systemId+
                            " is incorrect. Provide a correct one");
                    throw me;
                }
                EndpointDocInfo docInfo = new EndpointDocInfo(url, baos);
                DocInfo old = newMetadata.put(systemId, docInfo);
                if (old != null) {
                    logger.warning("Duplicate Source objects for systemId="+systemId+" in metadata");
                }
            }
            endpointInfo.setMetadata(newMetadata);
        }
    }
    
    /**
     * Finds primary WSDL
     */
    private void findPrimaryWSDL() throws Exception {
        Map<String, DocInfo> metadata = endpointInfo.getDocMetadata();
        // Checks whether the wsdlLocation resource is a primary wsdl 
        if (primaryWsdl != null) {
            DocInfo docInfo = metadata.get(primaryWsdl);
            if (docInfo.getService() == null) {
                throw new ServerRtException("not.primary.wsdl", primaryWsdl);
            }
        }
        // Checks whether metadata contains duplicate primary wsdls or abstract wsdsl
        if (metadata != null) {
            boolean concreteWsdl = false;
            boolean abstractWsdl = false;
            for(Entry<String, DocInfo> entry: metadata.entrySet()) {
                DocInfo docInfo = entry.getValue();
                if (docInfo.getService() != null) {
                    if (!concreteWsdl) {
                        concreteWsdl = true;
                    } else {
                        throw new ServerRtException("duplicate.primary.wsdl", entry.getKey());
                    }
                }
                if (docInfo.isHavingPortType()) {
                    if (!abstractWsdl) {
                        abstractWsdl = true;
                    } else {
                        throw new ServerRtException("duplicate.abstract.wsdl", entry.getKey());
                    }
                }
            }
        }
        if (metadata != null) {
            for(Entry<String, DocInfo> entry: metadata.entrySet()) {
                DocInfo docInfo = entry.getValue();
                if (docInfo.getService() != null) {
                    // Donot generate any WSDL or Schema document
                    URL wsdlUrl = new URL(entry.getKey());
                    EntityResolver resolver = new EndpointEntityResolver(metadata);
                    endpointInfo.setWsdlInfo(wsdlUrl, resolver);
                    docInfo.setQueryString("wsdl");
                    break;
                }
            }
        }
    }
    
    /**
     * Fills RuntimeEndpointInfo with ServiceName, and PortName from properties
     */
    public void fillEndpointInfo() throws Exception {
        // set Service Name from properties on RuntimeEndpointInfo
        setServiceName();
        
        // set Port Name from properties on RuntimeEndpointInfo
        setPortName();
        
        // Sets the correct Service Name
        endpointInfo.doServiceNameProcessing();
        
        // Sets the correct Port Name
        endpointInfo.doPortNameProcessing();
        
        // Sets the PortType Name
        endpointInfo.doPortTypeNameProcessing();
        
        // Creates DocInfo from metadata and sets it on RuntimeEndpointinfo
        setDocInfo();
        
        // Fill DocInfo with docuent info : WSDL or Schema, targetNS etc.
        RuntimeEndpointInfo.fillDocInfo(endpointInfo);
        
        // Finds primary WSDL from metadata documents
        findPrimaryWSDL();
        
    }
    
    /**
     * Generates necessary WSDL and Schema documents
     */
    public void generateWSDLDocs() {
        if (endpointInfo.needWSDLGeneration()) {
            endpointInfo.generateWSDL();
        }
    }
    
    public void publish(String address) {
        try {
            this.address = address;
            httpContext = ServerMgr.getInstance().createContext(address);
            try {
                publish(httpContext);
            } catch(Exception e) {
                ServerMgr.getInstance().removeContext(httpContext);
                throw e;
            }
        } catch(Exception e) {
            throw new ServerRtException("server.rt.err", e );
        }
    }

    public void publish(Object serverContext) {
        this.httpContext = (HttpContext)serverContext;
        try {
            publish(httpContext);
        } catch(Exception e) {
            throw new ServerRtException("server.rt.err", new Object[] { e } );
        }
    }

    public void stop() {
        if (address == null) {
            // Application created its own HttpContext
            httpContext.getServer().removeContext(httpContext);
        } else {
            // Remove HttpContext created by JAXWS runtime 
            ServerMgr.getInstance().removeContext(httpContext);
        }
        
        // Invoke WebService Life cycle method
        endpointInfo.endService();
    }

    private void publish (HttpContext context) throws Exception {
        endpointInfo.verifyImplementorClass();
        fillEndpointInfo();
        endpointInfo.init();
        generateWSDLDocs();
        RuntimeEndpointInfo.publishWSDLDocs(endpointInfo);
        logger.fine("Doc Metadata="+endpointInfo.getDocMetadata());
        WebServiceContext wsContext = new WebServiceContextImpl();
        endpointInfo.setWebServiceContext(wsContext);
        endpointInfo.injectContext();
        endpointInfo.beginService();
        Tie tie = new Tie();
        context.setHandler(new WSHttpHandler(tie, endpointInfo, executor));
    }    
    
}
