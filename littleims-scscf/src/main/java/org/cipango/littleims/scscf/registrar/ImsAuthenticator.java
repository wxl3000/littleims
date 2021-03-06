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
package org.cipango.littleims.scscf.registrar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.cipango.diameter.AVPList;
import org.cipango.diameter.api.DiameterServletAnswer;
import org.cipango.diameter.base.Common;
import org.cipango.diameter.ims.Cx;
import org.cipango.ims.AuthenticationScheme;
import org.cipango.littleims.scscf.cx.CxManager;
import org.cipango.littleims.scscf.registrar.AuthorizationException.Reason;
import org.cipango.littleims.scscf.util.MessageSender;
import org.cipango.littleims.scscf.util.NonceManager;
import org.cipango.littleims.util.AuthorizationHeader;
import org.cipango.littleims.util.Base64;
import org.cipango.littleims.util.Digest;
import org.cipango.littleims.util.Headers;
import org.cipango.littleims.util.URIHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImsAuthenticator implements Authenticator
{

	private static final Logger __log = LoggerFactory.getLogger(ImsAuthenticator.class);
	private static final int DEFAULT_AUTH_TIMEOUT = 5000; // 5s
	
	private Timer _timer;

	private CxManager _cxManager;
	private NonceManager _nonceManager;
	private SipFactory _sipFactory;
	private String _realm;
	private long _authTimeout = DEFAULT_AUTH_TIMEOUT;
	private Map<String, AuthWaitTimerTask> _secContexts = new HashMap<String, AuthWaitTimerTask>();
	private MessageSender _messageSender;
	
	public ImsAuthenticator()
	{
		_timer = new Timer();
		_nonceManager = new NonceManager();
	}


	/* (non-Javadoc)
	 * @see org.cipango.littleims.scscf.registrar.Authenticator#authenticate(boolean, javax.servlet.sip.SipServletRequest)
	 */
	public String authenticate(boolean proxy, SipServletRequest request) throws IOException
	{
		try
		{
			URI aor = URIHelper.getCanonicalForm(_sipFactory,  request.getTo().getURI());
			String authorization = request.getHeader(Headers.AUTHORIZATION);
			AuthorizationHeader ah = authorization == null ? null : new AuthorizationHeader(authorization);
						
			if ((ah == null || ah.getNonce() == null || ah.getNonce().trim().equals("")))
			{
				// No authorization, must be first REGISTER request
				// Inform registrar module and challenge UE
				_cxManager.sendMAR(aor, ah, request);
				
				return null;
			}
			else
			{
				try
				{
					// probably second REGISTER unless phone uses a stale nonce
					// parse Authorization header
					
					String privateUserIdentity = null;

					__log.debug("Authorizing registration ...");
					checkRegistration(aor, ah, request.getMethod());
					privateUserIdentity = ah.getUsername();
					
					// digest authentication is successful
					__log.debug("Registration of public identity " + aor + " has been authorized");
					
					return privateUserIdentity;
				}
				catch (AuthorizationException e)
				{
					switch (e.getReason())
					{
					case INVALID:
						__log.debug("Registration is not valid. Sending 400 response");
						_messageSender.sendResponse(request, SipServletResponse.SC_BAD_REQUEST);
						break;
					case ERROR:
						__log.debug("Credentials are not accepted. Sending 403 response");
						_messageSender.sendResponse(request, SipServletResponse.SC_FORBIDDEN);
						break;
					case STALE:
						__log.debug("Registration is stale. Sending 401 response");
						request.setAttribute("stale", true);
						_cxManager.sendMAR(aor, ah, request);
						break;
					case RESYNCHRONIZATION:
					case INITIAL:
						__log.debug("Registration is initial. Sending 401 response");
						_cxManager.sendMAR(aor, ah, request);
						break;
					default:
						__log.warn("Unknown reason " + e.getReason());
						break;
					}
					return null;
				}
			}
		}
		catch (IOException e)
		{
			__log.warn("Failed to send MAR request", e);
			_messageSender.sendResponse(request, SipServletResponse.SC_FORBIDDEN);

			return null;
				
		}
	}
	
	public CxManager getCxManager()
	{
		return _cxManager;
	}

	public void setCxManager(CxManager cxManager)
	{
		_cxManager = cxManager;
	}

	public void handleMaa(DiameterServletAnswer maa)
	{	
		SipServletRequest request = (SipServletRequest) maa.getRequest().getAttribute(SipServletRequest.class.getName());
		try
		{
			if (!maa.getResultCode().isSuccess())
			{
				__log.debug("Diameter MAA answer is not valid: " + maa.getResultCode() + ". Sending 403 response");
	
				_messageSender.sendResponse(request, SipServletResponse.SC_FORBIDDEN);
				return;
			}
			String aor = maa.getRequest().get(Cx.PUBLIC_IDENTITY);

			AVPList sadi =  maa.get(Cx.SIP_AUTH_DATA_ITEM);
			
			String scheme = sadi.getValue(Cx.SIP_AUTHENTICATION_SCHEME);
	
			if (AuthenticationScheme.SIP_DIGEST.getName().equals(scheme))
			{
				AVPList digestAuthenticate = sadi.getValue(Cx.SIP_DIGEST_AUTHENTICATE);
				String realm = digestAuthenticate.getValue(Common.DIGEST_REALM) == null ? _realm
						: digestAuthenticate.getValue(Common.DIGEST_REALM);
				String algorithm = digestAuthenticate.getValue(Common.DIGEST_ALGORITHM) == null ? AuthenticationScheme.SIP_DIGEST
						.getAlgorithm() : digestAuthenticate.getValue(Common.DIGEST_ALGORITHM);
	
				// Start Reg-await-auth timer
				AuthWaitTimerTask authTimer = new AuthWaitTimerTask(aor, digestAuthenticate.getValue(Common.DIGEST_HA1));
				_timer.schedule(authTimer, _authTimeout);
	
				synchronized (_secContexts)
				{
					_secContexts.put(aor, authTimer);
				}
				String nonce = _nonceManager.newNonce();
				__log.debug("Starting reg-await-auth timer for URI: " + aor + ". Nonce: "
								+ nonce);
				send401(request, realm, nonce, request.getAttribute("stale") != null, algorithm);
				
			}
			else if (AuthenticationScheme.DIGEST_AKA_MD5.getName().equals(scheme))
			{
				// TODO ik ck
				byte[] bNonce = sadi.getValue(Cx.SIP_AUTHENTICATE);
				String nonce = Base64.encode(bNonce);
				byte[] xres = sadi.getValue(Cx.SIP_AUTHORIZATION);
				AuthWaitTimerTask authTimer = new AuthWaitTimerTask(aor, xres);
				_timer.schedule(authTimer, _authTimeout);
	
				synchronized (_secContexts)
				{
					_secContexts.put(aor, authTimer);
				}
				send401(request, _realm, nonce, request.getAttribute("stale") != null, AuthenticationScheme.DIGEST_AKA_MD5.getAlgorithm());
			}
			else
			{
				__log.warn("Received unsupported scheme: " + scheme + " in MAA response");
				_messageSender.sendResponse(request, SipServletResponse.SC_FORBIDDEN);
			}
		}
		finally
		{
			// The SipApplicationSession can be invalid if this thread is interrupted before
			// sending the response.
			if (request.getApplicationSession().isValid())
				request.getApplicationSession().invalidate();
		}	
	}

	private void authWaitExpired(String uri)
	{
		__log.debug("reg-await-auth timer has expired for: " + uri);
		synchronized (_secContexts)
		{
			_secContexts.remove(uri);
		}
	}

	public void checkRegistration(URI uri, AuthorizationHeader ah, String method)
			throws AuthorizationException
	{

		// [TS24.229] 1. Check Call-ID
		// RFC 3261 does not mandate it. As a result, we don't check
		// since SIP phones are not IMS-compliant

		// [TS24.229] 2. Stop reg timer
		AuthWaitTimerTask authWaitTimerTask = null;
		synchronized (_secContexts)
		{
			authWaitTimerTask = _secContexts.get(uri.toString());
			if (authWaitTimerTask != null)
			{
				__log.debug("Found security context");
				authWaitTimerTask.cancel();
				_secContexts.remove(uri.toString());
			}
		}

		// [TS24.229] 3. Check if Authorization exists
		// Already done since use this header to start "protected REGISTER"
		// procedure, no integrity-protected header yet

		if (authWaitTimerTask == null)
		{
			__log.info("No security context found for user " + uri.toString());
			throw new AuthorizationException(Reason.STALE);
		}

		// [TS24.229] 4. Check the received authentication response
		String xres;
		try
		{
			switch (authWaitTimerTask.getAuthenticationScheme().getOrdinal())
			{
			case AuthenticationScheme.SIP_DIGEST_ORDINAL:
				xres = Digest.calculateResponseWithHa1(ah, method, authWaitTimerTask.getHa1());
				break;
			case AuthenticationScheme.DIGEST_AKA_MD5_ORDINAL:
				String auts = ah.getAuts();
				if (auts != null)
				{
					throw new AuthorizationException(Reason.RESYNCHRONIZATION);
				}
				xres = Digest.calculateResponse(ah, method, authWaitTimerTask.getXres());
				break;
			default:
				__log.warn("Authentication scheme: "
						+ authWaitTimerTask.getAuthenticationScheme().getName()
						+ " is not supported");
				throw new AuthorizationException(Reason.INVALID);
			}
		}
		catch (IllegalArgumentException _)
		{
			throw new AuthorizationException(Reason.INVALID);
		}

		String res = ah.getResponse();
		if (!res.equals(xres))
		{
			__log.info("Response is not correct for " + uri);
			throw new AuthorizationException(Reason.ERROR);
		}
	}

	private void send401(SipServletRequest request, String realm, String nonce, boolean stale,
			String algorithm)
	{
		try
		{
			__log.debug("Sending 401 response");
			String wwwAuthenticate = "Digest realm=\"" + realm + "\", qop=\"auth\", nonce=\"" + nonce
					+ "\", algorithm=" + algorithm;
			if (stale)
			{
				wwwAuthenticate += ", stale=TRUE";
			}
			_messageSender.sendResponse(request, SipServletResponse.SC_UNAUTHORIZED, Headers.WWW_AUTHENTICATE, wwwAuthenticate);
		}
		catch (Exception e)
		{
			__log.warn("Failed to send 401", e);
		}
	}
	
	public SipFactory getSipFactory()
	{
		return _sipFactory;
	}

	public void setSipFactory(SipFactory sipFactory)
	{
		_sipFactory = sipFactory;
	}

	public String getRealm()
	{
		return _realm;
	}

	public void setRealm(String realm)
	{
		_realm = realm;
	}
	
	class AuthWaitTimerTask extends TimerTask
	{
		private String _uri;
		private AuthenticationScheme _authenticationScheme;
		private byte[] _xres;
		private String _ha1;
		
		public AuthenticationScheme getAuthenticationScheme()
		{
			return _authenticationScheme;
		}

		public byte[] getXres()
		{
			return _xres;
		}

		public String getHa1()
		{
			return _ha1;
		}

		public AuthWaitTimerTask(String uri, byte[] xres)
		{
			_uri = uri;
			_authenticationScheme = AuthenticationScheme.DIGEST_AKA_MD5;
			_xres = xres;
		}
		
		public AuthWaitTimerTask(String uri, String ha1)
		{
			_uri = uri;
			_authenticationScheme = AuthenticationScheme.SIP_DIGEST;
			_ha1 = ha1;
		}

		public void run()
		{
			authWaitExpired(_uri);
		}

	}

	public MessageSender getMessageSender()
	{
		return _messageSender;
	}


	public void setMessageSender(MessageSender messageSender)
	{
		_messageSender = messageSender;
	}


	public long getAuthTimer()
	{
		return _authTimeout;
	}


	public void setAuthTimer(long authTimer)
	{
		_authTimeout = authTimer;
	}


}
