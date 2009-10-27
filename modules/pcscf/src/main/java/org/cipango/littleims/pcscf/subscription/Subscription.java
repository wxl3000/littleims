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
package org.cipango.littleims.pcscf.subscription;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface Subscription
{

	public String getUserAgent();
	
	public void handleSubscribeResponse(SipServletResponse response);
	
	public void handleNotify(SipServletRequest notify);
}
