package org.cipango.ims.hss.web.publicid;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.wicket.MarkupContainer;
import org.apache.wicket.PageParameters;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.RefreshingView;
import org.apache.wicket.markup.repeater.util.ModelIteratorAdapter;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.cipango.ims.hss.model.DebugSession;
import org.cipango.ims.hss.model.PrivateIdentity;
import org.cipango.ims.hss.model.PublicIdentity;
import org.cipango.ims.hss.model.PublicUserIdentity;
import org.cipango.ims.hss.model.Subscription;
import org.cipango.ims.hss.web.debugsession.EditDebugSessionPage;
import org.cipango.ims.hss.web.privateid.EditPrivateIdPage;
import org.cipango.ims.hss.web.serviceprofile.EditServiceProfilePage;
import org.cipango.ims.hss.web.serviceprofile.ViewServiceProfilePage;
import org.cipango.ims.hss.web.subscription.DeregistrationPage;
import org.cipango.ims.hss.web.subscription.EditImplicitSetPage;
import org.cipango.ims.hss.web.subscription.ViewSubscriptionPage;
import org.cipango.ims.oam.util.AutolinkBookmarkablePageLink;

@SuppressWarnings("unchecked")
public class ContextPanel extends Panel {

	
	public ContextPanel(PublicUserIdentity publicIdentity) {
		super("contextMenu");
		add(new AutolinkBookmarkablePageLink("editLink", EditPublicUserIdPage.class, 
				new PageParameters("id=" + publicIdentity.getIdentity())));
		add(new AutolinkBookmarkablePageLink("deleteLink", DeletePublicIdPage.class, 
				new PageParameters("id=" + publicIdentity.getIdentity())));
		add(new AutolinkBookmarkablePageLink("xmlSubscriptionLink", XmlSubscriptionPage.class, 
				new PageParameters("id=" + publicIdentity.getIdentity())));
		
		boolean foundSub = false;
		if (!publicIdentity.getPrivateIdentities().isEmpty())
		{
			Subscription subscription = publicIdentity.getSubscription();
			if (subscription != null)
			{
				add(new AutolinkBookmarkablePageLink("subscriptionLink", ViewSubscriptionPage.class, 
						new PageParameters("id=" + subscription.getName())));
				add(new AutolinkBookmarkablePageLink("implicitSetLink", EditImplicitSetPage.class, 
						new PageParameters("id=" + subscription.getName())));
				add(new AutolinkBookmarkablePageLink("deregistrationLink", DeregistrationPage.class, 
						new PageParameters("id=" + subscription.getName())).setVisible(subscription.getScscf() != null));
				foundSub = true;
			}
		}
		
		if (!foundSub)
		{
			add(new AutolinkBookmarkablePageLink("subscriptionLink", ViewSubscriptionPage.class).setVisible(false));
			add(new AutolinkBookmarkablePageLink("implicitSetLink", EditImplicitSetPage.class)).setVisible(false);
			add(new AutolinkBookmarkablePageLink("deregistrationLink", DeregistrationPage.class)).setVisible(false);
		}
		
		
		if (publicIdentity.getServiceProfile() == null)
			add(new AutolinkBookmarkablePageLink("serviceProfileLink", EditServiceProfilePage.class));
		else
			add(new AutolinkBookmarkablePageLink("serviceProfileLink", ViewServiceProfilePage.class, 
				new PageParameters("id=" + publicIdentity.getServiceProfile().getName())));

		addPrivateIds(publicIdentity);
		addImplicitSet(publicIdentity);
		addDebugSessions(publicIdentity);
	}
	
	private void addPrivateIds(PublicUserIdentity publicIdentity)
	{
		final List<String> privateIds = new ArrayList<String>();
		Iterator<PrivateIdentity> it = publicIdentity.getPrivateIdentities().iterator();
		while (it.hasNext())
			privateIds.add(it.next().getIdentity());
		
		add(new RefreshingView("privateIds"){

			@Override
			protected Iterator getItemModels()
			{
				return new ModelIteratorAdapter<String>(privateIds.iterator()) {

					@Override
					protected IModel<String> model(String id)
					{
						return new Model<String>(id);
					}
					
				};
			}

			@Override
			protected void populateItem(Item item)
			{
				MarkupContainer link = new AutolinkBookmarkablePageLink("identity", 
						EditPrivateIdPage.class, 
						new PageParameters("id=" + item.getModelObject()));
				item.add(link);
				link.add(new Label("name", item.getModel()));
			}
		});
		add(new AutolinkBookmarkablePageLink("newPrivateIdLink", EditPrivateIdPage.class, 
				new PageParameters("publicId=" + publicIdentity.getIdentity())));
		
	}
	
	private void addImplicitSet(PublicUserIdentity identity)
	{
		List<String> l = identity.getImplicitRegistrationSet().getPublicIds();
		l.remove(identity.getIdentity());
		add(new ListView("publicIds", l){

			@Override
			protected void populateItem(ListItem item)
			{
				MarkupContainer link = new AutolinkBookmarkablePageLink("identity", 
						EditPublicUserIdPage.class, 
						new PageParameters("id=" + item.getModelObject()));
				item.add(link);
				link.add(new Label("name", item.getModel()));
			}
		});
	}
	
	private void addDebugSessions(PublicIdentity publicIdentity)
	{
		final List<Long> sessions = new ArrayList<Long>();
		Iterator<DebugSession> it = publicIdentity.getDebugSessions().iterator();
		while (it.hasNext())
			sessions.add(it.next().getId());
		
		add(new RefreshingView("debugSessions"){

			@Override
			protected Iterator getItemModels()
			{
				return new ModelIteratorAdapter<Long>(sessions.iterator()) {

					@Override
					protected IModel<Long> model(Long id)
					{
						return new Model<Long>(id);
					}
					
				};
			}

			@Override
			protected void populateItem(Item item)
			{
				MarkupContainer link = new AutolinkBookmarkablePageLink("session", 
						EditDebugSessionPage.class, 
						new PageParameters("id=" + item.getModelObject()));
				item.add(link);
				link.add(new Label("id", item.getModel()));
			}
		});
		add(new AutolinkBookmarkablePageLink("newDebugSessionLink", EditDebugSessionPage.class, 
				new PageParameters("publicId=" + publicIdentity.getIdentity())));
	}


}
