/*
 * $HeadURL$
 * $Revision$
 * $Date$
 * 
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.conn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.SocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.localserver.LocalTestServer;
import org.apache.http.localserver.RandomHandler;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;

public class TestConnectionReuse extends TestCase {

    public TestConnectionReuse(String testName) {
        super(testName);
    }

    public static void main(String args[]) {
        String[] testCaseName = { TestConnectionReuse.class.getName() };
        junit.textui.TestRunner.main(testCaseName);
    }

    public static Test suite() {
        return new TestSuite(TestConnectionReuse.class);
    }

    protected LocalTestServer localServer;
    
    @Override
    protected void tearDown() throws Exception {
        if (this.localServer != null) {
            this.localServer.stop();
        }
    }


    public void testReuseOfPersistentConnections() throws Exception {
        BasicHttpProcessor httpproc = new BasicHttpProcessor();
        httpproc.addInterceptor(new ResponseDate());
        httpproc.addInterceptor(new ResponseServer());
        httpproc.addInterceptor(new ResponseContent());
        httpproc.addInterceptor(new ResponseConnControl());
        
        this.localServer = new LocalTestServer(httpproc, null);
        this.localServer.register("/random/*", new RandomHandler());
        this.localServer.start();

        InetSocketAddress saddress = (InetSocketAddress) this.localServer.getServiceAddress();
        
        HttpParams params = new BasicHttpParams();
        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
        HttpProtocolParams.setContentCharset(params, "UTF-8");
        HttpProtocolParams.setUserAgent(params, "TestAgent/1.1");
        HttpProtocolParams.setUseExpectContinue(params, false);
        HttpConnectionParams.setStaleCheckingEnabled(params, false);
        ConnManagerParams.setMaxTotalConnections(params, 5);
        ConnManagerParams.setMaxConnectionsPerRoute(params, 
                new ConnPerRouteBean(5));
        
        SchemeRegistry supportedSchemes = new SchemeRegistry();
        SocketFactory sf = PlainSocketFactory.getSocketFactory();
        supportedSchemes.register(new Scheme("http", sf, 80));
        
        ThreadSafeClientConnManager mgr = new ThreadSafeClientConnManager(
                params, supportedSchemes);

        DefaultHttpClient client = new DefaultHttpClient(mgr, params); 

        HttpHost target = new HttpHost(saddress.getHostName(), saddress.getPort(), "http");
        
        WorkerThread[] workers = new WorkerThread[10];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new WorkerThread(
                    client, 
                    target, 
                    new URI("/random/2000"), 
                    10, false);
        }
        
        for (int i = 0; i < workers.length; i++) {
            WorkerThread worker = workers[i];
            worker.start();
        }
        for (int i = 0; i < workers.length; i++) {
            WorkerThread worker = workers[i]; 
            workers[i].join(10000);
            Exception ex = worker.getException();
            if (ex != null) {
                throw ex;
            }
        }
        
        // Expect some connection in the pool
        assertTrue(mgr.getConnectionsInPool() > 0);

        mgr.shutdown();
    }

    private static class AlwaysCloseConn implements HttpResponseInterceptor {

        public void process(
                final HttpResponse response, 
                final HttpContext context) throws HttpException, IOException {
            response.setHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
        }
        
    }
    
    public void testReuseOfClosedConnections() throws Exception {
        BasicHttpProcessor httpproc = new BasicHttpProcessor();
        httpproc.addInterceptor(new ResponseDate());
        httpproc.addInterceptor(new ResponseServer());
        httpproc.addInterceptor(new ResponseContent());
        httpproc.addInterceptor(new AlwaysCloseConn());
        
        this.localServer = new LocalTestServer(httpproc, null);
        this.localServer.register("/random/*", new RandomHandler());
        this.localServer.start();

        InetSocketAddress saddress = (InetSocketAddress) this.localServer.getServiceAddress();
        
        HttpParams params = new BasicHttpParams();
        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
        HttpProtocolParams.setContentCharset(params, "UTF-8");
        HttpProtocolParams.setUserAgent(params, "TestAgent/1.1");
        HttpProtocolParams.setUseExpectContinue(params, false);
        HttpConnectionParams.setStaleCheckingEnabled(params, false);
        ConnManagerParams.setMaxTotalConnections(params, 5);
        ConnManagerParams.setMaxConnectionsPerRoute(params, 
                new ConnPerRouteBean(5));
        
        SchemeRegistry supportedSchemes = new SchemeRegistry();
        SocketFactory sf = PlainSocketFactory.getSocketFactory();
        supportedSchemes.register(new Scheme("http", sf, 80));
        
        ThreadSafeClientConnManager mgr = new ThreadSafeClientConnManager(
                params, supportedSchemes);

        DefaultHttpClient client = new DefaultHttpClient(mgr, params); 

        HttpHost target = new HttpHost(saddress.getHostName(), saddress.getPort(), "http");
        
        WorkerThread[] workers = new WorkerThread[10];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new WorkerThread(
                    client, 
                    target, 
                    new URI("/random/2000"), 
                    10, false);
        }
        
        for (int i = 0; i < workers.length; i++) {
            WorkerThread worker = workers[i]; 
            worker.start();
        }
        for (int i = 0; i < workers.length; i++) {
            WorkerThread worker = workers[i]; 
            workers[i].join(10000);
            Exception ex = worker.getException();
            if (ex != null) {
                throw ex;
            }
        }
        
        // Expect zero connections in the pool
        assertEquals(0, mgr.getConnectionsInPool());

        mgr.shutdown();
    }

    public void testReuseOfAbortedConnections() throws Exception {
        BasicHttpProcessor httpproc = new BasicHttpProcessor();
        httpproc.addInterceptor(new ResponseDate());
        httpproc.addInterceptor(new ResponseServer());
        httpproc.addInterceptor(new ResponseContent());
        httpproc.addInterceptor(new ResponseConnControl());
        
        this.localServer = new LocalTestServer(httpproc, null);
        this.localServer.register("/random/*", new RandomHandler());
        this.localServer.start();

        InetSocketAddress saddress = (InetSocketAddress) this.localServer.getServiceAddress();
        
        HttpParams params = new BasicHttpParams();
        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
        HttpProtocolParams.setContentCharset(params, "UTF-8");
        HttpProtocolParams.setUserAgent(params, "TestAgent/1.1");
        HttpProtocolParams.setUseExpectContinue(params, false);
        HttpConnectionParams.setStaleCheckingEnabled(params, false);
        ConnManagerParams.setMaxTotalConnections(params, 5);
        ConnManagerParams.setMaxConnectionsPerRoute(params, 
                new ConnPerRouteBean(5));
        
        SchemeRegistry supportedSchemes = new SchemeRegistry();
        SocketFactory sf = PlainSocketFactory.getSocketFactory();
        supportedSchemes.register(new Scheme("http", sf, 80));
        
        ThreadSafeClientConnManager mgr = new ThreadSafeClientConnManager(
                params, supportedSchemes);

        DefaultHttpClient client = new DefaultHttpClient(mgr, params); 

        HttpHost target = new HttpHost(saddress.getHostName(), saddress.getPort(), "http");
        
        WorkerThread[] workers = new WorkerThread[10];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new WorkerThread(
                    client, 
                    target, 
                    new URI("/random/2000"), 
                    10, true);
        }
        
        for (int i = 0; i < workers.length; i++) {
            WorkerThread worker = workers[i];
            worker.start();
        }
        for (int i = 0; i < workers.length; i++) {
            WorkerThread worker = workers[i]; 
            workers[i].join(10000);
            Exception ex = worker.getException();
            if (ex != null) {
                throw ex;
            }
        }
        
        // Expect zero connections in the pool
        assertEquals(0, mgr.getConnectionsInPool());

        mgr.shutdown();
    }

    private static class WorkerThread extends Thread {

        private final URI requestURI;
        private final HttpHost target;
        private final HttpClient httpclient;
        private final int repetitions;
        private final boolean forceClose;

        private volatile Exception exception;
        
        public WorkerThread(
                final HttpClient httpclient, 
                final HttpHost target, 
                final URI requestURI,
                int repetitions,
                boolean forceClose) {
            super();
            this.httpclient = httpclient;
            this.requestURI = requestURI;
            this.target = target;
            this.repetitions = repetitions;
            this.forceClose = forceClose;
        }
        
        @Override
        public void run() {
            try {
                for (int i = 0; i < this.repetitions; i++) {
                    HttpGet httpget = new HttpGet(this.requestURI);
                    HttpResponse response = this.httpclient.execute(
                            this.target, 
                            httpget);
                    if (this.forceClose) {
                        httpget.abort();
                    } else {
                        HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            entity.consumeContent();
                        }
                    }
                }
            } catch (Exception ex) {
                this.exception = ex;
            }
        }

        public Exception getException() {
            return exception;
        }
        
    }
    
}
