// ========================================================================
// Copyright 2008-2009 NEXCOM Systems
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

package org.cipango.ims.hss;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.Random;

import org.cipango.diameter.AVP;
import org.cipango.diameter.AVPList;
import org.cipango.diameter.ResultCode;
import org.cipango.diameter.Type;
import org.cipango.diameter.api.DiameterServletAnswer;
import org.cipango.diameter.api.DiameterServletRequest;
import org.cipango.diameter.base.Common;
import org.cipango.diameter.ims.Cx;
import org.cipango.diameter.ims.Cx.ServerAssignmentType;
import org.cipango.diameter.ims.Cx.UserAuthorizationType;
import org.cipango.diameter.ims.Cx.UserDataAlreadyAvailable;
import org.cipango.diameter.ims.Sh;
import org.cipango.ims.AuthenticationScheme;
import org.cipango.ims.hss.auth.AkaAuthenticationVector;
import org.cipango.ims.hss.auth.AuthenticationVector;
import org.cipango.ims.hss.auth.DigestAuthenticationVector;
import org.cipango.ims.hss.auth.Milenage;
import org.cipango.ims.hss.db.PrivateIdentityDao;
import org.cipango.ims.hss.db.PublicIdentityDao;
import org.cipango.ims.hss.db.ScscfDao;
import org.cipango.ims.hss.db.SubscriptionDao;
import org.cipango.ims.hss.diameter.DiameterException;
import org.cipango.ims.hss.model.ImplicitRegistrationSet.State;
import org.cipango.ims.hss.model.PSI;
import org.cipango.ims.hss.model.PrivateIdentity;
import org.cipango.ims.hss.model.PublicIdentity;
import org.cipango.ims.hss.model.PublicIdentity.IdentityType;
import org.cipango.ims.hss.model.PublicUserIdentity;
import org.cipango.ims.hss.model.RegistrationState;
import org.cipango.ims.hss.model.Scscf;
import org.cipango.ims.hss.model.Subscription;
import org.cipango.littleims.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

public class Hss
{
	public static final String __ISO_8859_1 = "ISO-8859-1";
	private static final Logger __log = LoggerFactory.getLogger(Hss.class);
	
	private PrivateIdentityDao _privateIdentityDao;
	private PublicIdentityDao _publicIdentityDao;
	private SubscriptionDao _subscriptionDao;
	private ScscfDao _scscfDao;
	private SequenceNumberManager _sequenceNumberManager = new SequenceNumberManager();
	private Random _random = new Random();
	
	public void setPrivateIdentityDao(PrivateIdentityDao dao)
	{
		_privateIdentityDao = dao;
	}
	
	@Transactional
	public void doLir(DiameterServletRequest lir) throws Exception
	{
		AVPList avps = lir.getAVPs();
		String impu = getMandatoryAVP(avps, Cx.PUBLIC_IDENTITY);
		
		PublicIdentity publicIdentity = getPublicIdentity(impu, null);
		
		if (publicIdentity instanceof PSI)
		{
			PSI psi = (PSI) publicIdentity;
			if (!psi.isPsiActivation())
				throw new DiameterException(Cx.DIAMETER_ERROR_USER_UNKNOWN,
						"PSI: " + impu + " has PSI Activation State set to inactive");
			
			// support to direct routing to AS
			if (psi.getApplicationServer() != null
					&& avps.get(Cx.ORIGININATING_REQUEST) == null)
			{
				DiameterServletAnswer lia = lir.createAnswer(Common.DIAMETER_SUCCESS);
				lia.add(Cx.SERVER_NAME, psi.getApplicationServer().getServerName());
				lia.send();
				return;
			}
			
			UserAuthorizationType userAuthorizationType = avps.getValue(Cx.USER_AUTHORIZATION_TYPE);
			if (userAuthorizationType == UserAuthorizationType.REGISTRATION_AND_CAPABILITIES)
			{
				// TODO support Server-Capabilities 
			}
		}

		Scscf scscf = publicIdentity.getScscf();
		Short state = publicIdentity.getState();
		if (state != State.REGISTERED)
		{
			if (avps.get(Cx.ORIGININATING_REQUEST) != null
					|| publicIdentity.getServiceProfile().hasUnregisteredServices())
			{
				if (scscf == null)
				{
					scscf = _scscfDao.findAvailableScscf();
					if (scscf == null)
						throw new DiameterException(Common.DIAMETER_UNABLE_TO_COMPLY, 
								"Coud not found any available SCSCF for public identity: " 
								+ publicIdentity.getIdentity());
					publicIdentity.setScscf(scscf);
				}	
			}
			else
			{
				lir.createAnswer(Cx.DIAMETER_ERROR_IDENTITY_NOT_REGISTERED).send();
				return;
			}
		}
		DiameterServletAnswer lia = lir.createAnswer(Common.DIAMETER_SUCCESS);
		lia.add(Cx.SERVER_NAME, scscf.getUri());
		if (publicIdentity.getIdentityType() == IdentityType.WILDCARDED_IMPU)
			lia.add(Cx.WILCARDED_IMPU, publicIdentity.getIdentity());
		if (publicIdentity.getIdentityType() == IdentityType.WILDCARDED_PSI)
			lia.add(Cx.WILCARDED_PSI, publicIdentity.getIdentity());

		lia.send();
		
	}
	
	
	@Transactional
	public void doUar(DiameterServletRequest uar) throws Exception
	{
		AVPList avps = uar.getAVPs();
		
		String impu = getMandatoryAVP(avps, Cx.PUBLIC_IDENTITY);

		String impi = getMandatoryAVP(avps, Common.USER_NAME);
		PrivateIdentity privateIdentity = _privateIdentityDao.findById(impi);
		
		if (privateIdentity == null)
			throw new DiameterException(Cx.DIAMETER_ERROR_USER_UNKNOWN,
					"Could not found private identity with IMPI: " + impi);
		
		PublicIdentity publicIdentity = getPublicIdentity(privateIdentity, impu);
		
		boolean emergencyReg = false;
		AVP<Integer> avp = avps.get(Cx.UAR_FLAGS);
		if (avp != null)
			emergencyReg = ((avp.getValue() & 0x01) == 0x01); // FIXME bit 0 is low or hight bit???
	
		if (publicIdentity.isBarred())
		{
			if (!emergencyReg)
			{
				boolean allBarred = true;
				for (PublicUserIdentity publicUserIdentity :privateIdentity.getPublicIdentities())
				{
					if (!publicUserIdentity.isBarred())
					{
						allBarred = false;
						break;
					}
				}
				if (allBarred)
				{
					__log.debug("LIR: publicIdentity " + publicIdentity.getIdentity() 
							+ " is barred, emergency flag is not set and all associated " +
									"public identities are barred, so send DIAMETER_AUTHORIZATION_REJECTED.");
					throw new DiameterException(Common.DIAMETER_AUTHORIZATION_REJECTED);
				}
			}
		}
		
		UserAuthorizationType userAuthorizationType = avps.getValue(Cx.USER_AUTHORIZATION_TYPE);
		if (userAuthorizationType == null || userAuthorizationType == UserAuthorizationType.REGISTRATION)
		{
			if (!emergencyReg)
			{
				// TODO check if roaming is allowed
			}
			
		} 
		else if (userAuthorizationType != null && userAuthorizationType == UserAuthorizationType.REGISTRATION_AND_CAPABILITIES)
		{
			// TODO add support to userAuthorizationType = REGISTRATION_AND_CAPABILITIES
		}
		
		Subscription subscription = privateIdentity.getSubscription();
		DiameterServletAnswer answer;
		Short state = publicIdentity.getState();	
		if (State.REGISTERED == state)
		{
			if (userAuthorizationType == null || userAuthorizationType == UserAuthorizationType.REGISTRATION)
			{
				answer = uar.createAnswer(Cx.DIAMETER_SUBSEQUENT_REGISTRATION);
			}
			else
			{
				// case UserAuthorizationType.DE_REGISTRATION
				answer = uar.createAnswer(Common.DIAMETER_SUCCESS);
			}
			answer.add(Cx.SERVER_NAME, subscription.getScscf().getUri());
		}
		else if (State.UNREGISTERED == state)
		{
			if (userAuthorizationType == null || userAuthorizationType == UserAuthorizationType.REGISTRATION)
			{
				answer = uar.createAnswer(Cx.DIAMETER_SUBSEQUENT_REGISTRATION);
			}
			else
			{
				// case UserAuthorizationType.DE_REGISTRATION
				answer = uar.createAnswer(Common.DIAMETER_SUCCESS);
			}
			answer.add(Cx.SERVER_NAME, subscription.getScscf().getUri());
		}
		else
		{
			if (userAuthorizationType != null && userAuthorizationType == UserAuthorizationType.DE_REGISTRATION)
				throw new DiameterException(Cx.DIAMETER_ERROR_IDENTITY_NOT_REGISTERED,
						"Public identity " + impu + " is not registered");
			else
			{
				if (subscription.getScscf() != null)
				{
					answer = uar.createAnswer(Cx.DIAMETER_SUBSEQUENT_REGISTRATION);
					answer.add(Cx.SERVER_NAME, subscription.getScscf().getUri());
				}
				else
				{
					Scscf scscf = _scscfDao.findAvailableScscf();
					if (scscf == null)
						throw new DiameterException(Common.DIAMETER_UNABLE_TO_COMPLY, 
								"Coud not found any available SCSCF for public identity: " 
								+ publicIdentity.getIdentity());

					subscription.setScscf(scscf);
					_subscriptionDao.save(subscription);
					answer = uar.createAnswer(Cx.DIAMETER_FIRST_REGISTRATION);
					answer.add(Cx.SERVER_NAME, scscf.getUri());
				}
			}
		}
		answer.send();
	}
	
	
	
	@Transactional
	public void doMar(DiameterServletRequest mar) throws Exception
	{
		AVPList avps = mar.getAVPs();
		
		String impi = getMandatoryAVP(avps, Common.USER_NAME);
		
		String impu = getMandatoryAVP(avps, Cx.PUBLIC_IDENTITY);

		PrivateIdentity privateIdentity = _privateIdentityDao.findById(impi);
		
		if (privateIdentity == null)
			throw new DiameterException(Cx.DIAMETER_ERROR_USER_UNKNOWN, 
					"Could not found private identity with IMPI: " + impi);
		
		PublicIdentity publicIdentity = getPublicIdentity(privateIdentity, impu);
		
		AVPList sadi =  avps.getValue(Cx.SIP_AUTH_DATA_ITEM);
		
		String s = sadi.getValue(Cx.SIP_AUTHENTICATION_SCHEME);
		
		AuthenticationScheme scheme = AuthenticationScheme.get(s);
		
		if (scheme == null)
			throw new DiameterException(Cx.DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED, 
					"Unknown scheme: " + s);

		DiameterServletAnswer answer = mar.createAnswer(Common.DIAMETER_SUCCESS);
		answer.getAVPs().add(Common.USER_NAME, impi);
		
		if (publicIdentity.getIdentityType() == IdentityType.WILDCARDED_IMPU)
			answer.add(Cx.WILCARDED_IMPU, publicIdentity.getIdentity());
		
		switch (scheme.getOrdinal())
		{
		case AuthenticationScheme.SIP_DIGEST_ORDINAL:
			AuthenticationVector[] authVectors = getDigestAuthVectors(1, mar.getDestinationRealm(), privateIdentity);
			
			answer.getAVPs().add(authVectors[0].asAuthItem());
			break;
			
		case AuthenticationScheme.DIGEST_AKA_MD5_ORDINAL:
			byte[] sipAuthorization = sadi.getValue(Cx.SIP_AUTHORIZATION);
			if (sipAuthorization != null)
				procesResynchronisation(sipAuthorization, privateIdentity);

			authVectors = getAkaAuthVectors(1, privateIdentity);			
			answer.getAVPs().add(authVectors[0].asAuthItem());
			break;
		default:
			throw new DiameterException(Cx.DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED);
		}
		answer.send();
	}
	
	private PublicIdentity getPublicIdentity(String impu, String wilcardImpu) throws DiameterException
	{
		PublicIdentity publicIdentity = null;
		if (wilcardImpu != null)
		{
			publicIdentity = _publicIdentityDao.findById(wilcardImpu);
			if (publicIdentity == null && __log.isDebugEnabled())
				__log.warn("Could not found public identity with wilcarded IMPU or wilcarded PSI " + wilcardImpu);
		}
		if (publicIdentity == null)
			publicIdentity = _publicIdentityDao.findById(impu);
		if (publicIdentity == null)
			publicIdentity = _publicIdentityDao.findWilcard(impu);
		if (publicIdentity == null)
			throw new DiameterException(Cx.DIAMETER_ERROR_USER_UNKNOWN,
					"Could not find public identity with IMPU: " + impu);
		return publicIdentity;
	}
	
	private PublicIdentity getPublicIdentity(PrivateIdentity privateIdentity, String impu) throws DiameterException
	{
		for (PublicUserIdentity id : privateIdentity.getPublicIdentities())
		{
			if (id.getIdentity().equals(impu) || (id.isWilcard() && impu.matches(id.getRegex())))
				return id;
		}
		
		throw new DiameterException(Cx.DIAMETER_ERROR_IDENTITIES_DONT_MATCH,
				"Private identity : " + privateIdentity.getIdentity() + " does not have associated IMPU: " + impu);
	}
	
	@Transactional
	public void doSar(DiameterServletRequest sar) throws Exception 
	{
		// See 3GPP TS 29-228 �6.1.2.1
		AVPList avps = sar.getAVPs();
		
		String impi = avps.getValue(Common.USER_NAME);
		
		String impu = getMandatoryAVP(avps, Cx.PUBLIC_IDENTITY);
		
		ServerAssignmentType serverAssignmentType = getMandatoryAVP(avps, Cx.SERVER_ASSIGNMENT_TYPE);

		// TODO use list for public identity
		PublicIdentity publicIdentity = null;
		PrivateIdentity privateIdentity;

		if (impu != null)
		{
			String wilcardImpu = avps.getValue(Cx.WILCARDED_IMPU);
			if (wilcardImpu == null)
				wilcardImpu = avps.getValue(Cx.WILCARDED_PSI);
			publicIdentity = getPublicIdentity(impu, wilcardImpu);
		}
		
		if (impi != null)
		{
			privateIdentity = _privateIdentityDao.findById(impi);
			if (privateIdentity == null)
				throw new DiameterException(Cx.DIAMETER_ERROR_USER_UNKNOWN,
						"Unknown private identity: " + impu);
		}
		else if (publicIdentity != null)
		{
			if (publicIdentity instanceof PublicUserIdentity)
			{
				PublicUserIdentity userId = (PublicUserIdentity) publicIdentity;
				privateIdentity = userId.getPrivateIdentities().iterator().next();
				impi = privateIdentity.getIdentity();
			}
			else
			{
				impi = ((PSI) publicIdentity).getPrivateServiceIdentity();
				privateIdentity = null;
			}
		}
		else
			throw DiameterException.newMissingDiameterAvp(Cx.PUBLIC_IDENTITY);
		
		if (publicIdentity == null)
		{
			if (privateIdentity != null)
			{
				publicIdentity = privateIdentity.getPublicIdentities().iterator().next();
				impu = publicIdentity.getIdentity();
			}
			else
				throw DiameterException.newMissingDiameterAvp(Cx.PUBLIC_IDENTITY);
		}
		
		if (impu == null 
				&& serverAssignmentType != ServerAssignmentType.TIMEOUT_DEREGISTRATION
				&& serverAssignmentType != ServerAssignmentType.USER_DEREGISTRATION
				&& serverAssignmentType != ServerAssignmentType.DEREGISTRATION_TOO_MUCH_DATA
				&& serverAssignmentType != ServerAssignmentType.TIMEOUT_DEREGISTRATION_STORE_SERVER_NAME
				&& serverAssignmentType != ServerAssignmentType.USER_DEREGISTRATION_STORE_SERVER_NAME 
				&& serverAssignmentType != ServerAssignmentType.ADMINISTRATIVE_DEREGISTRATION)
			throw DiameterException.newMissingDiameterAvp(Cx.PUBLIC_IDENTITY);
		
		if (impi == null && serverAssignmentType != ServerAssignmentType.UNREGISTERED_USER)
			throw DiameterException.newMissingDiameterAvp(Common.USER_NAME);
		
			
		String serverName = getMandatoryAVP(avps, Cx.SERVER_NAME);
						
		boolean userDataAlreadyAvailable = 
			getMandatoryAVP(avps, Cx.USER_DATA_ALREADY_AVAILABLE) == UserDataAlreadyAvailable.USER_DATA_ALREADY_AVAILABLE;
		
		if (publicIdentity instanceof PSI
				&& !((PSI) publicIdentity).isPsiActivation())
			throw new DiameterException(Cx.DIAMETER_ERROR_USER_UNKNOWN, 
					"The PSI: " + publicIdentity.getIdentity() + " has PSI activation state set to inactive");
		
		DiameterServletAnswer answer = sar.createAnswer(Common.DIAMETER_SUCCESS);
		Short state = publicIdentity.getState();
		Scscf scscf = publicIdentity.getScscf();
		switch (serverAssignmentType)
		{
		case REGISTRATION:
		case RE_REGISTRATION:
			if (scscf != null && !serverName.equals(scscf.getUri()))
			{
				AVP<String> avp = new AVP<String>(Cx.SERVER_NAME, scscf.getUri());
				throw new DiameterException(Cx.DIAMETER_ERROR_IDENTITY_ALREADY_REGISTERED,
						"S-CSCF " + scscf.getUri() + " assigned to " + publicIdentity.getIdentity()
						+ " does not match with server name " + serverName).addAvp(avp);
			}

			publicIdentity.updateState(impi, State.REGISTERED);
			answer.getAVPs().add(Common.USER_NAME, impi);
			if (!userDataAlreadyAvailable)
			{
				String serviceProfile = publicIdentity.getImsSubscriptionAsXml(impi, impu, false);
				answer.getAVPs().add(Sh.USER_DATA, serviceProfile.getBytes());
			}
			AVPList associatedIds = getAssociatedIdentities(privateIdentity);
			if (!associatedIds.isEmpty())
				answer.getAVPs().add(Cx.ASSOCIATED_IDENTITIES, associatedIds);
			break;
			
		case UNREGISTERED_USER:
			if (scscf != null && !serverName.equals(scscf.getUri()))
			{
				AVP<String> avp = new AVP<String>(Cx.SERVER_NAME, scscf.getUri());
				throw new DiameterException(Cx.DIAMETER_ERROR_IDENTITY_ALREADY_REGISTERED).addAvp(avp);
			}
			else
			{
				scscf = _scscfDao.findByUri(serverName);
				if (scscf == null)
					throw new IllegalArgumentException("Could not find S-CSCF with URI: " + serverName);
				publicIdentity.setScscf(scscf);
			}
			
			if (State.NOT_REGISTERED == state || State.REGISTERED == state)
				publicIdentity.updateState(impi, State.UNREGISTERED);
			
			answer.getAVPs().add(Common.USER_NAME, impi);
			
			if (!userDataAlreadyAvailable)
			{
				String serviceProfile = publicIdentity.getImsSubscriptionAsXml(null, impu, false);
				answer.getAVPs().add(Sh.USER_DATA, serviceProfile.getBytes());
			}
			
			associatedIds = getAssociatedIdentities(privateIdentity);
			if (!associatedIds.isEmpty())
				answer.getAVPs().add(Cx.ASSOCIATED_IDENTITIES, associatedIds);

			break;
			
		case TIMEOUT_DEREGISTRATION:
		case USER_DEREGISTRATION:
		case DEREGISTRATION_TOO_MUCH_DATA:
		case ADMINISTRATIVE_DEREGISTRATION:
			publicIdentity.updateState(impi, State.NOT_REGISTERED);
			if (State.REGISTERED == state)
				checkClearScscf(publicIdentity);
			break;
			
		case TIMEOUT_DEREGISTRATION_STORE_SERVER_NAME:
		case USER_DEREGISTRATION_STORE_SERVER_NAME:
			publicIdentity.updateState(impi, State.UNREGISTERED);
			break;
			
		case NO_ASSIGNMENT:
			if (scscf == null)
				throw new IllegalStateException("No S-CSCF assigned");
			else if (!serverName.equals(scscf.getUri()))
				throw new IllegalStateException("Requesting S-CSCF: " + serverName 
						+ " is not the same as the assigned S-CSCF: " + scscf.getUri());
				
			if (!userDataAlreadyAvailable)
			{
				String serviceProfile = publicIdentity.getImsSubscriptionAsXml(impi, impu, false);
				answer.add(Sh.USER_DATA, serviceProfile.getBytes());
			}
			associatedIds = getAssociatedIdentities(privateIdentity);
			if (!associatedIds.isEmpty())
				answer.getAVPs().add(Cx.ASSOCIATED_IDENTITIES, associatedIds);
			break;
			
		case AUTHENTICATION_FAILURE:
		case AUTHENTICATION_TIMEOUT:
			if (publicIdentity instanceof PublicUserIdentity)
			{
				PublicUserIdentity publicUserIdentity = (PublicUserIdentity) publicIdentity;
				RegistrationState registrationState = publicUserIdentity.getImplicitRegistrationSet().getRegistrationState(impu);
				if (registrationState.getState() == State.AUTH_PENDING)
					registrationState.setState(State.NOT_REGISTERED);
				
				checkClearScscf(publicIdentity);
			}
			break;
		default:
			throw new IllegalArgumentException("Unsuported ServerAssignmentType: " + serverAssignmentType);
		}
		answer.send();
	}
	
	private AVPList getAssociatedIdentities(PrivateIdentity privateIdentity)
	{

		AVPList associatedIds = new AVPList();
		if (privateIdentity == null)
			return associatedIds;
		
		for (String identity : privateIdentity.getSubscription().getPrivateIds())
		{
			if (!privateIdentity.getIdentity().equals(identity))
				associatedIds.add(Common.USER_NAME, identity);
		}
		return associatedIds;
	}
	
	private void checkClearScscf(PublicIdentity publicIdentity)
	{
		if (publicIdentity instanceof PublicUserIdentity)
		{
			boolean activePublic = false;
			Subscription subscription = 
				((PublicUserIdentity) publicIdentity).getPrivateIdentities().iterator().next().getSubscription();
			for (PublicIdentity publicId : subscription.getPublicIdentities())
			{
				Short state = publicId.getState();
				if (State.NOT_REGISTERED != state)
					activePublic = true;
			}
			if (!activePublic)
				subscription.setScscf(null);
		}
		else
			publicIdentity.setScscf(null);
	}
	

	protected <T> T getMandatoryAVP(AVPList avps, Type<T> type) throws DiameterException
	{
		AVP<T> avp = avps.get(type);
		if (avp == null)
			throw DiameterException.newMissingDiameterAvp(type);
		return avp.getValue();
	}
	
	
	
	protected AuthenticationVector[] getDigestAuthVectors(int nb, String realm, PrivateIdentity identity)
	{
		DigestAuthenticationVector vector = new DigestAuthenticationVector();
		
		byte[] ha1;
		try
		{
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(identity.getIdentity().getBytes(__ISO_8859_1));
			md.update((byte)':');
			md.update(realm.getBytes(__ISO_8859_1));
			md.update((byte)':');
			md.update(identity.getPassword());
			ha1 = md.digest();
		}
		catch (Exception e)
		{
			return null; // TODO
		}
		String sha1 = HexString.bufferToHex(ha1);
		
		vector.setRealm(realm);
		vector.setHA1(sha1);
		
		return new AuthenticationVector[] {vector};
	}
	
	protected AuthenticationVector[] getAkaAuthVectors(int nb, PrivateIdentity identity) throws InvalidKeyException, ArrayIndexOutOfBoundsException, UnsupportedEncodingException
	{
		byte[] sqn = _sequenceNumberManager.getNextSqn(identity.getSqn());
		byte[] rand = new byte[16];
		_random.nextBytes(rand);
		AkaAuthenticationVector vector = new AkaAuthenticationVector(
				identity.getAkaPassword(),
				sqn,
				rand,
				identity.getOperatorId());
		identity.setSqn(sqn);
		
		return new AuthenticationVector[] {vector};
	}

	public void setSubscriptionDao(SubscriptionDao subscriptionDao)
	{
		_subscriptionDao = subscriptionDao;
	}

	public void setScscfDao(ScscfDao scscfDao)
	{
		_scscfDao = scscfDao;
	}

	public void setPublicIdentityDao(PublicIdentityDao publicIdentityDao)
	{
		_publicIdentityDao = publicIdentityDao;
	}
	
	protected void procesResynchronisation(byte[] sipAuthorization, PrivateIdentity identity) {
		__log.debug("SQN Desynchronization detected on user " + identity.getIdentity());
		byte[] sqn = new byte[6];
		byte[] macs = new byte[8];
		
		byte[] rand = new byte[16];
		byte[] auts = new byte[sqn.length + macs.length];
		for (int i = 0; i < sipAuthorization.length; i++)
		{
			if (i < rand.length)
				rand[i] = sipAuthorization[i];
			else
				auts[i - rand.length] = sipAuthorization[i];
		}


		try
		{
			byte[] k = identity.getAkaPassword();
			byte[] opC = Milenage.computeOpC(k, identity.getOperatorId());
			byte[] ak = Milenage.f5star(k, rand, opC);
			for (int i = 0; i < auts.length; i++) {
				if (i < sqn.length) {
					sqn[i] =  (byte) (auts[i] ^ ak[i]);
				} else {
					macs[i - sqn.length] = auts[i];
				}
			}
			byte[] computeMacs = Milenage.f1star(k, rand, opC, sqn, AkaAuthenticationVector.getAmf());
			for (int i = 0; i < macs.length; i++) {
				if (computeMacs[i] != macs[i]) {
					__log.info("MACS verification failed user " + identity.getIdentity());
					return;
				}
			}
			identity.setSqn(sqn);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public void doPpa(DiameterServletAnswer ppa)
	{
		ResultCode resultCode = ppa.getResultCode();
		if (!resultCode.isSuccess())
		{
			__log.warn("Received negative response code to DPR: " + resultCode);
			// TODO deregister.
		}
	}
	
	public void doRta(DiameterServletAnswer rta)
	{
		ResultCode resultCode = rta.getResultCode();
		if (!resultCode.isSuccess())
		{
			__log.warn("Received negative response code to RTR: " + resultCode);
		}
	}

}
