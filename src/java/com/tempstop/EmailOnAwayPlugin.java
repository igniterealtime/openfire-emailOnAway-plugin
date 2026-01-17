package com.tempstop;

import org.jivesoftware.openfire.MessageRouter;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.openfire.vcard.VCardManager;
import org.jivesoftware.util.EmailService;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import java.io.File;

public class EmailOnAwayPlugin implements Plugin, PacketInterceptor
{
    /**
     * Controls if the e-mail address of the intended recipient is sent in the confirmation message that is sent to the
     * originator.
     */
    public final SystemProperty<Boolean> SHOW_EMAIL = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.emailonaway.showemail")
        .setPlugin("Email on Away")
        .setDefaultValue(true)
        .setDynamic(true)
        .build();

    /**
     * The subject of emails that are being sent.
     */
    public final SystemProperty<String> EMAIL_SUBJECT = SystemProperty.Builder.ofType(String.class)
        .setKey("plugin.emailonaway.email.subject")
        .setPlugin("Email on Away")
        .setDefaultValue("IM")
        .setDynamic(true)
        .build();

    /**
     * The plain-text body of emails that are being sent. $$IMBODY$$ will be replaced with the body of the chat message that was missed.
     */
    public final SystemProperty<String> EMAIL_BODY_PLAIN = SystemProperty.Builder.ofType(String.class)
        .setKey("plugin.emailonaway.email.body.plain")
        .setPlugin("Email on Away")
        .setDefaultValue("$$IMBODY$$")
        .setDynamic(true)
        .build();

    /**
     * The html-text body of emails that are being sent. $$IMBODY$$ will be replaced with the body of the chat message that was missed.
     */
    public final SystemProperty<String> EMAIL_BODY_HTML = SystemProperty.Builder.ofType(String.class)
        .setKey("plugin.emailonaway.email.body.html")
        .setPlugin("Email on Away")
        .setDefaultValue("")
        .setDynamic(true)
        .build();

    /**
     * Controls if the XMPP address is used as an e-mail address if no other email address could be looked up.
     */
    public final SystemProperty<Boolean> USE_XMPP_AS_EMAIL_ADDRESS = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.emailonaway.use_xmpp_as_email_address")
        .setPlugin("Email on Away")
        .setDefaultValue(true)
        .setDynamic(true)
        .build();

    /**
     * The default email address, to be used when no email address can be looked up for a user.
     */
    public final SystemProperty<String> DEFAULT_EMAIL_ADDRESS = SystemProperty.Builder.ofType(String.class)
        .setKey("plugin.emailonaway.email.default_mail_address")
        .setPlugin("Email on Away")
        .setDefaultValue("no-reply@" + XMPPServer.getInstance().getServerInfo().getXMPPDomain())
        .setDynamic(true)
        .build();

    private static final Logger Log = LoggerFactory.getLogger(EmailOnAwayPlugin.class);
    private final InterceptorManager interceptorManager;
    private final UserManager userManager;
    private final PresenceManager presenceManager;
    private final EmailService emailService;
    private final MessageRouter messageRouter;
    private final VCardManager vcardManager;

    public EmailOnAwayPlugin() {
        interceptorManager = InterceptorManager.getInstance();
        emailService = EmailService.getInstance();
        messageRouter = XMPPServer.getInstance().getMessageRouter();
        presenceManager = XMPPServer.getInstance().getPresenceManager();
        userManager = XMPPServer.getInstance().getUserManager();
        vcardManager = VCardManager.getInstance();
    }

    public void initializePlugin(PluginManager pManager, File pluginDirectory) {
        // register with interceptor manager
        interceptorManager.addInterceptor(this);
    }

    public void destroyPlugin() {
        // unregister with interceptor manager
        interceptorManager.removeInterceptor(this);
    }

    private Message createServerMessage(JID to, JID from, String emailTo) {
        final Message message = new Message();
        message.setTo(to);
        message.setFrom(from);
        message.setSubject("I'm away");
        if (SHOW_EMAIL.getValue()) {
            message.setBody("I'm currently away. Your message has been forwarded to my email address  (" + emailTo + ").");
        } else {
            message.setBody("I'm currently away. Your message has been forwarded to my email address.");
        }
        return message;
    }

    public void interceptPacket(Packet packet, Session session, boolean read,
                                boolean processed) throws PacketRejectedException
    {
        String emailTo;
        String emailFrom;

        if((!processed) &&
            (!read) &&
            (packet instanceof Message) &&
            (packet.getTo() != null))
        {
            Message msg = (Message) packet;

            if (msg.getType() != Message.Type.chat) {
                return;
            }
            if (!XMPPServer.getInstance().isLocal(packet.getTo())) {
                return;
            }
            try {
                final User userTo = userManager.getUser(packet.getTo().getNode());
                if (!presenceManager.getPresence(userTo).toString().toLowerCase().contains("away")) {
                    // Status isn't away
                    return;
                }

                if (msg.getBody() == null) {
                    return;
                }

                // Build email
                emailTo = determineEmailAddress(packet.getTo());
                emailFrom = determineEmailAddress(packet.getFrom());

                // Send email
                emailService.sendMessage(
                    determineName(packet.getTo()),
                    emailTo,
                    determineName(packet.getFrom()),
                    emailFrom,
                    EMAIL_SUBJECT.getValue(),
                    EMAIL_BODY_PLAIN.getValue().isEmpty() ? null : EMAIL_BODY_PLAIN.getValue().replace("$$IMBODY$$", msg.getBody()),
                    EMAIL_BODY_HTML.getValue().isEmpty() ? null : EMAIL_BODY_HTML.getValue().replace("$$IMBODY$$", msg.getBody()));

                // Notify the sender that this went to email/sms
                messageRouter.route(createServerMessage(packet.getFrom().asBareJID(), packet.getTo().asBareJID(), emailTo));

                Log.trace("Sent an email to 'away' user '{}' that received a chat message from '{}'", packet.getTo(), packet.getFrom());
            }
            catch (UserNotFoundException e) {
                Log.debug("Unable to determine if an email should be sent to a user that is away, as the user '{}' or '{}'  cannot be found.", packet.getTo(), packet.getFrom(), e);
            }
        }
    }

    /**
     * Find a human-readable name for an XMPP address
     *
     * @param xmppAddress The XMPP address for what to look up a name
     * @return The name belonging to the XMPP address
     */
    public String determineName(final JID xmppAddress)
    {
        try {
            if (XMPPServer.getInstance().isLocal(xmppAddress)) {
                final String username = xmppAddress.getNode();
                String name = vcardManager.getVCardProperty(username, "FN");
                if (name != null && !name.isEmpty()) {
                    return name;
                }

                String givenName = vcardManager.getVCardProperty(username, "N:GIVEN");
                String familyName = vcardManager.getVCardProperty(username, "N:FAMILY");
                if (givenName != null && !givenName.isEmpty()) {
                    name = givenName;
                }
                if (familyName != null && !familyName.isEmpty()) {
                    if (name != null) {
                        name += " ";
                        name += familyName;
                    } else {
                        name = familyName;
                    }
                }
                if (name != null && !name.isEmpty()) {
                    return name;
                }

                name = vcardManager.getVCardProperty(username, "NICKNAME");
                if (name != null && !name.isEmpty()) {
                    return name;
                }

                name = userManager.getUser(username).getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (UserNotFoundException e) {
            Log.debug("Unable to find user for '{}'", xmppAddress.getNode(), e);
        }

        return "Chat User " + xmppAddress.toBareJID();
    }

    /**
     * Find an email address belonging to the same user as the XMPP address
     *
     * @param xmppAddress The XMPP address for what to look up a name
     * @return The email address belonging to the same user as the owner of the XMPP address
     */
    public String determineEmailAddress(final JID xmppAddress)
    {
        try {
            if (XMPPServer.getInstance().isLocal(xmppAddress)) {
                final String username = xmppAddress.getNode();
                String email = vcardManager.getVCardProperty(username, "EMAIL");
                if (email != null && !email.isEmpty()) {
                    return email;
                }

                email = vcardManager.getVCardProperty(username, "EMAIL:USERID");
                if (email != null && !email.isEmpty()) {
                    return email;
                }

                email = userManager.getUser(username).getEmail();
                if (email != null && !email.isEmpty()) {
                    return email;
                }
            }
        } catch (UserNotFoundException e) {
            Log.debug("Unable to find user for '{}'", xmppAddress.getNode(), e);
        }

        if (USE_XMPP_AS_EMAIL_ADDRESS.getValue()) {
            return xmppAddress.toBareJID();
        } else {
            return DEFAULT_EMAIL_ADDRESS.getValue();
        }
    }
}
