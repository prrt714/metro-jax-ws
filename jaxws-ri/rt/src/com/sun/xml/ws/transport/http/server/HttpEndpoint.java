
/**
 * $Id: HttpEndpoint.java,v 1.1 2005-08-16 22:24:09 jitu Exp $
 *
 * Copyright 2005 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.sun.xml.ws.transport.http.server;

import com.sun.xml.ws.server.DocInfo;
import com.sun.xml.ws.server.RuntimeEndpointInfo;
import com.sun.xml.ws.server.ServerRtException;
import com.sun.xml.ws.server.Tie;
import com.sun.xml.ws.server.WSDLPatcher;
import com.sun.xml.ws.util.localization.LocalizableMessageFactory;
import com.sun.xml.ws.util.localization.Localizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpInteraction;
import com.sun.xml.ws.handler.MessageContextImpl;
import com.sun.xml.ws.spi.runtime.MessageContext;
import com.sun.xml.ws.spi.runtime.WebServiceContext;

import javax.xml.ws.Endpoint;
import javax.xml.ws.Binding;
import java.util.List;
import javax.xml.transform.Source;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.logging.Logger;



/**
 *
 * @author WS Development Team
 */
public class HttpEndpoint implements Endpoint {
    
    private Localizer localizer;
    private LocalizableMessageFactory messageFactory;
    private static final Logger logger =
        Logger.getLogger(
            com.sun.xml.ws.util.Constants.LoggingDomain + ".server.http");
    
    private String address;
    private HttpContext httpContext;
    private HttpServer httpServer;
    private boolean published;
    private RuntimeEndpointInfo endpointInfo;
    
    private static final int MAX_THREADS = 5;
    private static final String GET_METHOD = "GET";
    private static final String POST_METHOD = "POST";
    private static final String HTML_CONTENT_TYPE = "text/html";
    private static final String XML_CONTENT_TYPE = "text/xml";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    
    public HttpEndpoint(RuntimeEndpointInfo rtEndpointInfo) {
        this.endpointInfo = rtEndpointInfo;
        localizer = new Localizer();
        messageFactory =
            new LocalizableMessageFactory("com.sun.xml.ws.resources.httpserver");
    }
    
    public Binding getBinding() {
        return endpointInfo.getBinding();
    }

    public Object getImplementor() {
        return endpointInfo.getImplementor();
    }

    public void publish(String address) {
        try {
            this.address = address;
            URL url = new URL(address);
            int port = url.getPort();
            if (port == -1) {
                port = url.getDefaultPort();
            }
            InetSocketAddress inetAddress = new InetSocketAddress(port);
            HttpServer server = HttpServer.create(inetAddress, MAX_THREADS);
            server.setExecutor(Executors.newFixedThreadPool(5));
            int index = url.getPath().lastIndexOf('/');
            String contextRoot = url.getPath().substring(0, index);
            System.out.println("*** contextRoot ***"+contextRoot);
            HttpContext context = server.createContext(contextRoot);
            publish(context);
            server.start();
        } catch(Exception e) {
            throw new ServerRtException("server.rt.err", new Object[] { e } );
        }
    }

    public void publish(Object serverContext) {
        if (!(serverContext instanceof HttpContext)) {
            throw new ServerRtException("not.HttpContext.type");
        }
        this.httpContext = (HttpContext)serverContext;
        try {
            publish(httpContext);
        } catch(Exception e) {
            throw new ServerRtException("server.rt.err", new Object[] { e } );
        }
        published = true;
    }

    public void stop() {
        // TODO: what should be done in unpublish ?
        httpContext.setHandler(null);
        if (httpServer != null) {
            httpServer.removeContext(httpContext);
            httpServer.terminate(0);
        }
        published = false;
    }

    public boolean isPublished() {
        return published;
    }

    public java.util.List<Source> getMetadata() {
        return endpointInfo.getMetadata();
    }

    public void setMetadata(java.util.List<Source> metadata) {
        endpointInfo.setMetadata(metadata);
    }

    public Executor getExecutor() {
        return null;
    }

    public void setExecutor(Executor executor) {
        //ToDO
    }

    public Map<String, Object> getProperties() {
        return null;
    }

    public void setProperties(Map<String, Object> map) {

    }

    public void publish (HttpContext context) throws Exception {
        endpointInfo.setUrlPattern("");
        endpointInfo.deploy();
        if (endpointInfo.needWSDLGeneration()) {
            endpointInfo.generateWSDL();
        }
        final WebServiceContext wsContext = new WebServiceContextImpl();
        endpointInfo.setWebServiceContext(wsContext);
        System.out.println("Doc Metadata="+endpointInfo.getDocMetadata());
        
        final Tie tie = new Tie();
        context.setHandler(new HttpHandler() {
            public void handleInteraction(HttpInteraction msg) {
                try {
                    System.out.println("Received HTTP request:"+msg.getRequestURI());
                    String method = msg.getRequestMethod();
                    if (method.equals(GET_METHOD)) {
                        InputStream is = msg.getRequestBody();
                        readFully(is);
                        is.close();
                        writeGetReply(msg, endpointInfo);
                    } else if (method.equals(POST_METHOD)) {
                        ServerConnectionImpl con = new ServerConnectionImpl(msg);
                        MessageContext msgCtxt = new MessageContextImpl();
                        wsContext.setMessageContext(msgCtxt);
                        tie.handle(con, endpointInfo);
                        //con.getOutput().close();
                    } else {
                        logger.warning(
                                localizer.localize(
                                    messageFactory.getMessage(
                                        "unexpected.http.method", method)));
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        msg.close();
                    } catch(IOException ioe) {
                        ioe.printStackTrace();          // Not much can be done
                    }
                }
            }
        });
    }
    
    /*
     * Consumes the entire input stream
     */
    private static void readFully(InputStream is) throws IOException {
        byte[] buf = new byte[1024];
        if (is != null) {
            while (is.read(buf) != -1);
        }
    }
            
    protected void writeGetReply(HttpInteraction msg, RuntimeEndpointInfo targetEndpoint)
    throws Exception {
     
        String queryString = msg.getRequestURI().getQuery();
        System.out.println("queryString="+queryString);

        String inPath = targetEndpoint.getPath(queryString);
        if (inPath == null) {
            writeNotFoundErrorPage(msg, "Invalid Request="+msg.getRequestURI());
            return;
        }
        DocInfo in = targetEndpoint.getDocMetadata().get(inPath);
        if (in == null) {
            writeNotFoundErrorPage(msg, "Invalid Request="+msg.getRequestURI());
            return;
        }
        msg.getResponseHeaders().addHeader(CONTENT_TYPE_HEADER, XML_CONTENT_TYPE);
        msg.sendResponseHeaders(HttpURLConnection.HTTP_OK,  0);
        OutputStream outputStream = msg.getResponseBody();
        
        List<RuntimeEndpointInfo> endpoints = new ArrayList<RuntimeEndpointInfo>();
        endpoints.add(targetEndpoint);
        WSDLPatcher patcher = new WSDLPatcher(inPath, address,
                targetEndpoint, endpoints, in.getDocContext());
        InputStream is = in.getDoc();
        try {
            patcher.patchDoc(is, outputStream);
        } finally {
            is.close();
            //outputStream.close();
        }
         
    }
    
    /*
     * writes 404 Not found error html page
     */
    private void writeNotFoundErrorPage(HttpInteraction msg, String message)
    throws IOException {
        msg.getResponseHeaders().addHeader(CONTENT_TYPE_HEADER, HTML_CONTENT_TYPE);
        msg.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND,  0);
        OutputStream outputStream = msg.getResponseBody();
        PrintWriter out = new PrintWriter(outputStream);
        out.println("<html><head><title>");
        out.println(
            localizer.localize(
                messageFactory.getMessage("html.title")));
        out.println("</title></head><body>");
        out.println(
            localizer.localize(
                messageFactory.getMessage("html.notFound", message)));
        out.println("</body></html>");
        out.close();
    }
            
    
}
