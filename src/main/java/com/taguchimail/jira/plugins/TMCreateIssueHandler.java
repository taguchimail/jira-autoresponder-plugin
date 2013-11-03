package com.taguchimail.jira.plugins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.StringUtils;
import static org.apache.commons.lang.StringUtils.join;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.mail.Email;
import com.atlassian.jira.notification.NotificationRecipient;
import com.atlassian.jira.plugins.mail.handlers.CreateIssueHandler;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.service.util.handler.MessageHandlerExecutionMonitor;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.mail.MailUtils;
import com.atlassian.mail.MailFactory;
import com.atlassian.mail.server.SMTPMailServer;


/**
 * An Issue Handler that sends 'received' notification to the sender
 *
 */
public class TMCreateIssueHandler extends CreateIssueHandler {

    private String issueKey = null;
    public static final String KEY_ISSUE_KEY = "issueKey";
    public static final String CC_CUSTOM_FIELD_NAME = "CC";

    /**
     * Sends a receipt email to the address.
     */
    private void sendReceipt(Message message, MessageHandlerContext context) throws Exception
    {
        String from = null;
        String servername = null;
        String serverdesc = null;
        String username = null;
        String smtpPort = null;
        String toAddress = MailUtils.getSenders(message).get(0);

        try {
            String body = MailUtils.getBody(message);
            StringBuilder responseMessage = new StringBuilder(128);
            StringBuilder newSubject = new StringBuilder(128);
            InternetAddress[] addresses = InternetAddress.parse(InternetAddress.toString(message.getFrom()));
            String firstname = addresses[0].getPersonal().split("\\s+")[0];

            SMTPMailServer mailserver = MailFactory.getServerManager().getDefaultSMTPMailServer();

            if (mailserver == null) {
                // FIXME display this in the UI...
                context.getMonitor().warning("You currently do not have an smtp mail server set up yet.");
                return;
            }

            from = mailserver.getDefaultFrom();
            servername = mailserver.getName();
            serverdesc = mailserver.getDescription();
            username = mailserver.getUsername();
            smtpPort = mailserver.getPort();

            // Response message
            responseMessage.append("Hi ");
            responseMessage.append(firstname);
            responseMessage.append(",\n\nWe have allocated ");
            if (issueKey != null) {
                newSubject.append("Re: [" + issueKey + "] ");
                responseMessage.append("ticket ");
                responseMessage.append(issueKey + " ");
            } else {
                responseMessage.append("a ticket ");
            }

            if (message != null && message.getSubject() != null && message.getSubject().length() > 0) {
                newSubject.append(message.getSubject());
            } else {
                newSubject.append("Your request has been received");
            }

            responseMessage.append("for your request, and our support team will address it shortly. Should you need to communicate to us further regarding this ticket, please ensure ");
            responseMessage.append(issueKey);
            responseMessage.append(" is in your email subject line.\n\nRegards,\n\nTeam Support\n\n\n");
            responseMessage.append("-------------------------------\n\n");
            responseMessage.append(body);

            Multipart multipart = new MimeMultipart();
            BodyPart bodypart = new MimeBodyPart();
            bodypart.setText(responseMessage.toString());
            multipart.addBodyPart(bodypart);

            if (NotificationRecipient.MIMETYPE_HTML.equals("html")) {
                mailserver.send(new Email(toAddress).setSubject(newSubject.toString()).setMultipart(multipart).setMimeType("text/html"));
            }
            else {
                mailserver.send(new Email(toAddress).setSubject(newSubject.toString()).setMultipart(multipart));
            }

            context.getMonitor().info("Auto-response sent to " + toAddress + " with subject line '" + newSubject.toString() + "'");

        } catch (Exception e) {
            context.getMonitor().error("An error occurred with sending receipt email to " + toAddress + ":\n" + ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * Traps the issue key in the parent class.
     */
    @Override protected Collection<ChangeItemBean> createAttachmentsForMessage(final Message message, final Issue issue, final MessageHandlerContext context) throws IOException, MessagingException
    {
        issueKey = issue.getKey();
        return super.createAttachmentsForMessage(message, issue, context);
    }

    /**
     * Sends a receipt email after an issue is created successfully.
     */
    @Override public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException 
    {
        boolean isHandled = super.handleMessage(message, context);

        if (!isHandled) {
            return isHandled;
        }

        // Add CC users to the ticket for reference
        IssueManager issueManager = ComponentAccessor.getIssueManager();
        MutableIssue issueObj = issueManager.getIssueObject(issueKey);
        CustomField ccField = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName(CC_CUSTOM_FIELD_NAME);
        Collection<String> recipients = getAllRecipientsFromEmail(message.getRecipients(Message.RecipientType.CC));
        User reporter = getReporter(message, context);
        recipients.remove(reporter.getEmailAddress());
        if (!recipients.isEmpty()) {
            final String ccRecipients = join(recipients, ",");
            if (context.isRealRun()) {
                issueObj.setCustomFieldValue(ccField, ccRecipients.toString());
                issueManager.updateIssue(reporter, issueObj, EventDispatchOption.ISSUE_UPDATED, false);
            } else {
                MessageHandlerExecutionMonitor messageHandlerExecutionMonitor = context.getMonitor();
                messageHandlerExecutionMonitor.info("Adding CC recipients " + ccRecipients);
                log.debug("CC recipients [" + ccRecipients + "] not added due to dry-run mode");
            }
        }

        // Send a receipt
        try {
            // Don't reply to messages with Precedence: Bulk,
            // delivery status, or auto-generated messages, as
            // well as empty "From" email.
            if ((!"bulk".equalsIgnoreCase(getPrecedenceHeader(message)) || !isDeliveryStatus(message) || !isAutoSubmitted(message)) && message.getFrom() != null && message.getFrom().length > 0) {
                if (context.isRealRun()) {
                    sendReceipt(message, context);
                } else {
                    MessageHandlerExecutionMonitor messageHandlerExecutionMonitor = context.getMonitor();
                    messageHandlerExecutionMonitor.info("Sent autoresponse message..");
                    log.debug("Autoresponse not sent due to dry-run mode");
                }

            } else {
                if (context.isRealRun()) {
                    log.debug("Email is bulk. Auto-closing..");
                    IssueService issueService = ComponentAccessor.getIssueService();
                    IssueInputParameters issueInputParameters = issueService.newIssueInputParameters();
                    issueInputParameters.setComment("Closing.");

                    IssueService.TransitionValidationResult transitionValidationResult = issueService.validateTransition(reporter, issueObj.getId(), 6, issueInputParameters);

                    if (transitionValidationResult.isValid()) {
                        IssueService.IssueResult transitionResult = issueService.transition(reporter, transitionValidationResult);
                        if (!transitionResult.isValid()) {
                            // Do something
                            log.debug("Transition result is not valid");
                        }
                    } else {
                        // Do something
                        log.debug("Transition validation result is not valid");
                    }
                } else {
                    MessageHandlerExecutionMonitor messageHandlerExecutionMonitor = context.getMonitor();
                    messageHandlerExecutionMonitor.info("Email is bulk. Closing...");
                    log.debug("Issue wasn't closed due to dry-run mode");
                }
            }
        } catch (Exception e) {
            context.getMonitor().warning(getI18nBean().getText("admin.mail.unable.to.create.issue"), e);
        }        

        return isHandled;
    }

    public Collection<String> getAllRecipientsFromEmail(Address addresses[])
    {
        if (addresses == null || addresses.length == 0) {
            return Collections.emptyList();
        }
        final List<String> recipients = new ArrayList<String>();
        for (Address address : addresses) {
            String emailAddress = getEmailAddress(address);
            if (emailAddress != null) {
                // Exclude JIRA users as they are already added as
                // .. Watchers, if CC watchers is enabled
                if (UserUtils.getUserByEmail(emailAddress) == null) {
                    recipients.add(emailAddress);
                }
            }
        }
        return recipients;                
    }

    private String getEmailAddress(Address address)
    {
        if (address instanceof InternetAddress)
        {
            InternetAddress internetAddress = (InternetAddress) address;
            return internetAddress.getAddress();
        }
        return null;
    }
}
