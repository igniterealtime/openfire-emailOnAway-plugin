package com.tempstop;

import org.jivesoftware.openfire.MessageRouter;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.vcard.VCardManager;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.EmailService;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

//import java.io.File;
import java.io.*;

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

            if(msg.getType() == Message.Type.chat) {
                try {
                    User userTo = userManager.getUser(packet.getTo().getNode());
                    if(presenceManager.getPresence(userTo).toString().toLowerCase().contains("away")) {
                        // Status isn't away
                        if(msg.getBody() != null) {
                            // Build email/sms
                            // The to email address
                            emailTo = vcardManager.getVCardProperty(userTo.getUsername(), "EMAIL");
                            if(emailTo == null || emailTo.isEmpty()) {
                                emailTo = vcardManager.getVCardProperty(userTo.getUsername(), "EMAIL:USERID");
                                if(emailTo == null || emailTo.isEmpty()) {
                                    emailTo = userTo.getEmail();
                                    if(emailTo == null || emailTo.isEmpty()) {
                                        emailTo = packet.getTo().getNode() + "@" + packet.getTo().getDomain();
                                    }
                                }
                            }
                            // The From email address
                            User userFrom = userManager.getUser(packet.getFrom().getNode());
                            emailFrom = vcardManager.getVCardProperty(userFrom.getUsername(), "EMAIL");
                            if(emailFrom == null || emailFrom.isEmpty()) {
                                emailFrom = vcardManager.getVCardProperty(userFrom.getUsername(), "EMAIL:USERID");
                                if(emailFrom == null || emailFrom.isEmpty()) {
                                    emailFrom = userFrom.getEmail();
                                    if(emailFrom == null || emailFrom.isEmpty()) {
                                        emailFrom = packet.getFrom().getNode() + "@" + packet.getFrom().getDomain();
                                    }
                                }
                            }

                            // Send email/sms
                            // if this is an sms... modify the recipient address
                            emailService.sendMessage(userTo.getName(),
                                emailTo,
                                userFrom.getName(),
                                emailFrom,
                                "IM",
                                msg.getBody(),
                                null);

                            // Notify the sender that this went to email/sms
                            messageRouter.route(createServerMessage(packet.getFrom().asBareJID(), packet.getTo().asBareJID(), emailTo));
                        }
                    }
                } catch (UserNotFoundException e) {
                    Log.debug("Unable to determine if an email should be sent to a user that is away, as the user '{}' cannot be found.", packet.getTo(), e);
                }
            }
        }
    }
}
