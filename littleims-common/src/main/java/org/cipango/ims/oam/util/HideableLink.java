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
package org.cipango.ims.oam.util;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.IAjaxCallDecorator;
import org.apache.wicket.ajax.calldecorator.AjaxCallDecorator;
import org.apache.wicket.ajax.markup.html.AjaxLink;

@SuppressWarnings("unchecked")
public class HideableLink extends AjaxLink
{	
	private String _markupId;
	
	@Deprecated
	public HideableLink(String id, String markupId)
	{
		super(id);
		_markupId = markupId;
		setOutputMarkupId(true);
	}
	
	public HideableLink(String id, Component parent)
	{
		super(id);
		parent.setOutputMarkupId(true);
		_markupId = parent.getMarkupId();
		setOutputMarkupId(true);
	}
	
	public IAjaxCallDecorator getAjaxCallDecorator() 
	{
		return new AjaxCallDecorator() 
		{
			public CharSequence decorateScript(CharSequence script)
			{
				return "var wcall=0;hide('" + _markupId + "','" + getMarkupId(true)
						+ "','" + getString("elementPanel.show") + "','" + getString("elementPanel.hide") + "');";
			}
		};
	}
	@Override
	public void onClick(AjaxRequestTarget target)
	{
	}	
}