// ========================================================================
// Copyright 2009 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================
package org.cipango.littleims.pcscf.subscription.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.cipango.littleims.pcscf.subscription.Subscription;
import org.cipango.littleims.pcscf.subscription.SubscriptionServlet;
import org.cipango.littleims.util.Headers;
import org.cipango.littleims.util.Methods;
import org.cipango.littleims.util.URIHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugIdService
{
	private static final Logger _log = LoggerFactory.getLogger(DebugIdService.class);
	public static final String EVENT_DEBUG = "debug";
	
	private SipFactory _sipFactory;
	private SipURI _pcscfUri;
	
	private Map<String, DebugSubscription> _subscriptions = new HashMap<String, DebugSubscription>();
	private Map<String, DebugConf> _confs = new ConcurrentHashMap<String, DebugConf>();
	private String _userAgent;
	
	/**
	 * See 3GPP 24.229 �5.2.3A	Subscription to the user's debug event package 
	 * @param aor
	 */
	public void subscribe(URI aor, int expires)
	{
		synchronized (_subscriptions)
		{
			try
			{
				DebugSubscription subscription = _subscriptions.get(aor.toString());
				
				if (subscription == null || !subscription.getSession().isValid())
				{
					SipServletRequest request = _sipFactory.createRequest(
							_sipFactory.createApplicationSession(),
							Methods.SUBSCRIBE,
							_pcscfUri,
							aor);
					request.addHeader(Headers.EVENT, EVENT_DEBUG);
					request.addHeader(Headers.P_ASSERTED_IDENTITY, _pcscfUri.toString());
					request.setExpires(expires);
					if (_userAgent != null)
						request.setHeader(Headers.USER_AGENT, _userAgent);
						
					subscription = new DebugSubscription(this, request.getSession(), aor.toString());
					_subscriptions.put(aor.toString(), subscription);
					request.getApplicationSession().setAttribute(Subscription.class.getName(), 
							subscription);
					request.getSession().setHandler(SubscriptionServlet.class.getSimpleName());
					request.send();
					_log.debug("Start debug subscription of user " + aor);
				}
				else
				{
					SipServletRequest request = subscription.getSession().createRequest(Methods.SUBSCRIBE);
					request.addHeader(Headers.EVENT, EVENT_DEBUG);
					request.addHeader(Headers.P_ASSERTED_IDENTITY, _pcscfUri.toString());
					request.setExpires(expires);
					if (_userAgent != null)
						request.setHeader(Headers.USER_AGENT, _userAgent);
					request.send();
				}
			}
			catch (Exception e)
			{
				_log.warn("Failed to SUBSCRIBE to debug event", e);
			}
		}
	}
	
	public void handleDebug(SipServletMessage message)
	{
		if (message instanceof SipServletRequest)
		{
			SipServletRequest request = (SipServletRequest) message;
			if (request.isInitial())
			{

				boolean orig = request.getTo().getURI().equals(request.getRequestURI());
				DebugConf debugConf = _confs.get(getServerUser(request, orig).toString());
				if (debugConf != null)
				{
					debugConf.checkStartLogging(request);
				}
				return;
			}
		}
		else
		{
			SipServletRequest request = ((SipServletResponse) message).getRequest();
			if (request != null)
			{
				String debugId = request.getHeader(Headers.P_DEBUG_ID);
				if (debugId != null)
					message.setHeader(Headers.P_DEBUG_ID, debugId);
			}
		}
		
		
		DebugSession debugSession = (DebugSession) message.getApplicationSession().getAttribute(DebugSession.class.getName());
		if (debugSession != null && !message.isCommitted())
		{
			message.setHeader(Headers.P_DEBUG_ID, debugSession.getDebugId());
			debugSession.checkStopLogging(message);
		}
	}
	
	private URI getServerUser(SipServletRequest request, boolean originating) 
	{
		try
		{
			Address served = request.getAddressHeader(Headers.P_SERVED_USER);
			if (served == null)
			{
				if (originating)
				{
					// Couple of sanity checks firs
					served = request.getAddressHeader(Headers.P_PREFERRED_IDENTITY);
					if (served == null)
						served = request.getFrom();
				}
				else
					return URIHelper.getCanonicalForm(_sipFactory, request.getRequestURI());
			}
			return URIHelper.getCanonicalForm(_sipFactory, served.getURI());
		}
		catch (ServletParseException e)
		{
			_log.warn(e.getMessage(), e);
			if (originating)
				return request.getFrom().getURI();
			else
				return request.getRequestURI();
		}
	}
	
	public void addDebugConf(DebugConf conf)
	{
		_confs.put(conf.getAor(), conf);
	}
	
	public void removeDebugConf(DebugConf conf)
	{
		_confs.remove(conf.getAor());
	}
	
	public void removeSubscription(DebugSubscription subscription)
	{
		synchronized (_subscriptions)
		{
			_subscriptions.remove(subscription.getAor());
		}
	}
	
	public Map<String, DebugConf> getDebugConfs()
	{
		return _confs;
	}
	
	public Map<String, DebugSubscription> getDebugSubscriptions()
	{
		return _subscriptions;
	}

	public SipFactory getSipFactory()
	{
		return _sipFactory;
	}

	public void setSipFactory(SipFactory sipFactory)
	{
		_sipFactory = sipFactory;
	}

	public SipURI getPcscfUri()
	{
		return _pcscfUri;
	}

	public void setPcscfUri(SipURI pcscfUri)
	{
		_pcscfUri = pcscfUri;
	}

	public String getUserAgent()
	{
		return _userAgent;
	}

	public void setUserAgent(String userAgent)
	{
		_userAgent = userAgent;
	}
}
