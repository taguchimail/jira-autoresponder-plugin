package com.taguchimail.jira.plugins;

import javax.mail.Message;
import javax.mail.MessagingException;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;

import com.atlassian.jira.plugins.mail.handlers.CreateOrCommentHandler;
import com.atlassian.jira.plugins.mail.handlers.FullCommentHandler;
import com.atlassian.jira.plugins.mail.handlers.NonQuotedCommentHandler;

/**
 * A message handler to create a new issue and send a 'received'
 * receipt to the sender, or add a comment to an existing issue from
 * an incoming message. If the subject contains a project key the
 * message is added as a comment to that issue. If no project key is
 * found, a new issue is created in the default project.
 */
 public class TMCreateOrCommentHandler extends CreateOrCommentHandler {

    /**
     * If set (to anything except "false"), quoted text is removed from comments.
     */
    public String stripquotes;
    private static final String FALSE = "false";

    /**
     * Mostly copied from the parent, except using TMCreateIssueHandler instead
     * of JIRA's CreateIssueHandler.
     */
    @Override public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException
    {
        String subject = message.getSubject();

        if (!canHandleMessage(message, context.getMonitor()))
        {
            if (log.isDebugEnabled())
            {
                log.debug("Cannot handle message '" + subject + "'.");
            }

            return deleteEmail;
        }

        if (log.isDebugEnabled())
        {
            log.debug("Looking for Issue Key in subject '" + subject + "'.");
        }
        Issue issue = ServiceUtils.findIssueObjectInString(subject);

        if (issue == null)
        {
            // If we cannot find the issue from the subject of the e-mail message
            // try finding the issue using the in-reply-to message id of the e-mail message
            log.debug("Issue Key not found in subject '" + subject + "'. Inspecting the in-reply-to message ID.");
            issue = getAssociatedIssue(message);
        }

        // if we have found an associated issue
        if (issue != null)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Issue '" + issue.getKey() + "' found for email '" + subject + "'.");
            }
            boolean doDelete = false;

            //add the message as a comment to the issue
            if ((stripquotes == null) || FALSE.equalsIgnoreCase(stripquotes)) //if stripquotes not defined in setup
            {
                FullCommentHandler fc = new FullCommentHandler()
                {
                    @Override
                    protected MessageUserProcessor getMessageUserProcessor()
                    {
                        return TMCreateOrCommentHandler.this.getMessageUserProcessor();
                    }
                };
                fc.init(params, context.getMonitor());
                doDelete = fc.handleMessage(message, context); //get message with quotes
            }
            else
            {
                NonQuotedCommentHandler nq = new NonQuotedCommentHandler()
                {
                    @Override
                    protected MessageUserProcessor getMessageUserProcessor()
                    {
                        return TMCreateOrCommentHandler.this.getMessageUserProcessor();
                    }
                };

                nq.init(params, context.getMonitor());
                doDelete = nq.handleMessage(message, context); //get message without quotes
            }
            return doDelete;
        }
        else
        { //no issue found, so create new issue in default project
            if (log.isDebugEnabled())
            {
                log.debug("No Issue found for email '" + subject + "' - creating a new Issue.");
            }

            TMCreateIssueHandler createIssueHandler = new TMCreateIssueHandler()
            {
                @Override
                protected MessageUserProcessor getMessageUserProcessor()
                {
                    return TMCreateOrCommentHandler.this.getMessageUserProcessor();
                }
            };

            createIssueHandler.init(params, context.getMonitor());
            return createIssueHandler.handleMessage(message, context);
        }
    }
}
