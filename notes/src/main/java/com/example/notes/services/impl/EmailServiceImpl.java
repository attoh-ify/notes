package com.example.notes.services.impl;

import com.example.notes.entities.noteAccess.NoteAccessRole;
import com.example.notes.services.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    @Value("${spring.mail.username}")
    private String fromEmailId;

    private final JavaMailSender javaMailSender;

    private static final Logger log =
            LoggerFactory.getLogger(EmailServiceImpl.class);

    private final String emailHeader = """
        <div style="text-align: center; padding: 25px 0; background-color: #f8fbf9; border-radius: 8px 8px 0 0; border-bottom: 1px solid #e2e8f0;">
            <h1 style="color: #2F855A; margin: 0; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; letter-spacing: 4px; font-weight: 800;">NOTES</h1>
            <p style="color: #718096; font-size: 11px; margin: 5px 0 0 0; text-transform: uppercase; font-weight: 600;">Your thoughts, synchronized.</p>
        </div>
        """;

    private final String emailFooter = """
        <div style="margin-top: 30px; padding-top: 20px; border-top: 1px solid #edf2f7; text-align: center;">
            <p style="font-size: 12px; color: #a0aec0; line-height: 1.5;">
                &copy; 2026 Notes App. Optimized for live collaboration.<br/>
                This is an automated system notification.
            </p>
        </div>
        """;

    public EmailServiceImpl(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
    }

    private void sendHtmlEmail(String recipient, String subject, String htmlContent) {
//        try {
//            MimeMessage message = javaMailSender.createMimeMessage();
//            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
//
//            helper.setFrom(fromEmailId);
//            helper.setTo(recipient);
//            helper.setSubject(subject);
//            helper.setText(htmlContent, true);
//
//            javaMailSender.send(message);
//        } catch (MessagingException e) {
//            log.error("Failed to send HTML email to {}", recipient, e);
//        }
    }

    @Override
    public void sendRegisterEmail(String actorEmail) {
        String subject = "Welcome to Notes – Synchronize Your Thoughts";
        String body = """
            <html>
            <body style="font-family: sans-serif; color: #2d3748; background-color: #f7fafc; padding: 20px;">
                <div style="max-width: 600px; margin: 0 auto; background: white; padding: 40px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.05);">
                    %s
                    <h2 style="margin-top: 30px; font-size: 24px;">Welcome to the future of note-taking.</h2>
                    <p>Hello <strong>%s</strong>,</p>
                    <p>Notes is more than just a digital notebook—it's a live, collaborative engine built for speed and security.</p>
                    <div style="background-color: #f0fff4; border-left: 4px solid #38a169; padding: 20px; margin: 25px 0;">
                        <ul style="margin: 0; padding: 0; list-style: none;">
                            <li style="margin-bottom: 10px;">🚀 <strong>Live Collaboration:</strong> Edit with others in real-time.</li>
                            <li style="margin-bottom: 10px;">🛡️ <strong>Granular Roles:</strong> Viewer, Editor, or Super access.</li>
                            <li>🌍 <strong>Instant Visibility:</strong> Toggle between Public and Private notes.</li>
                        </ul>
                    </div>

                    <div style="text-align: center; margin: 40px 0;">
                        <a href="http://localhost:3000" style="background-color: #2F855A; color: white; padding: 15px 35px; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 16px;">Create Your First Note</a>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(emailHeader, actorEmail, emailFooter);

        sendHtmlEmail(actorEmail, subject, body);
    }

    @Override
    public void sendAccessGrantedEmail(String recipientEmail, String noteTitle, NoteAccessRole role) {
        String subject = "New Collaboration Invite: " + noteTitle;
        String roleBadge = buildRoleBadge(role);

        String body = """
            <html>
            <body style="font-family: sans-serif; color: #2d3748; padding: 20px;">
                <div style="max-width: 600px; margin: 0 auto; border: 1px solid #e2e8f0; padding: 40px; border-radius: 12px;">
                    %s
                    <h3 style="margin-top: 25px;">You've Been Added</h3>
                    <p>Hello,</p>
                    <p>You have been invited to collaborate on the note: <strong>"%s"</strong>.</p>
                    <div style="background-color: #f7fafc; padding: 25px; border-radius: 10px; margin: 25px 0; border: 1px solid #edf2f7;">
                        <span style="font-size: 11px; color: #a0aec0; text-transform: uppercase; font-weight: bold; letter-spacing: 1px;">Assigned Role</span>
                        %s
                    </div>

                    <div style="text-align: center; margin-top: 35px;">
                        <a href="http://localhost:3000/notes" style="background-color: #2d3748; color: white; padding: 12px 25px; text-decoration: none; border-radius: 6px; font-weight: 600;">Open Workspace</a>
                    </div>
                    %s
                </div>
            </body>
            </html>
            """.formatted(emailHeader, noteTitle, roleBadge, emailFooter);

        sendHtmlEmail(recipientEmail, subject, body);
    }

    @Override
    public void sendAccessUpdatedEmail(String recipientEmail, String noteTitle, NoteAccessRole newRole) {
        String subject = "Access Updated: " + noteTitle;
        String roleBadge = buildRoleBadge(newRole);

        String body = """
            <html>
            <body style="font-family: sans-serif; color: #2d3748; padding: 20px;">
                <div style="max-width: 600px; margin: 0 auto; border: 1px solid #e2e8f0; padding: 40px; border-radius: 12px;">
                    %s
                    <div style="text-align: center; margin-bottom: 20px;">
                        <span style="background-color: #ebf8ff; color: #3182ce; padding: 5px 15px; border-radius: 50px; font-size: 11px; font-weight: 800; text-transform: uppercase;">Permission Change</span>
                    </div>
                    <h3>Role Updated</h3>
                    <p>Your access level for <strong>"%s"</strong> has been modified by the note owner.</p>
                    <div style="background-color: #fffaf0; padding: 25px; border-radius: 10px; margin: 25px 0; border: 1px dashed #f6ad55;">
                        <span style="font-size: 11px; color: #a0aec0; text-transform: uppercase; font-weight: bold; letter-spacing: 1px;">New Permissions</span>
                        %s
                    </div>
                    <p style="font-size: 14px; color: #718096;">These changes take effect immediately.</p>
                    %s
                </div>
            </body>
            </html>
            """.formatted(emailHeader, noteTitle, roleBadge, emailFooter);

        sendHtmlEmail(recipientEmail, subject, body);
    }

    @Override
    public void sendAccessDeletedEmail(String recipientEmail, String noteTitle) {
        String subject = "Access Revoked: " + noteTitle;
        String body = """
            <html>
            <body style="font-family: sans-serif; color: #2d3748; padding: 20px;">
                <div style="max-width: 600px; margin: 0 auto; border: 1px solid #fed7d7; padding: 40px; border-radius: 12px; background-color: #fffafaf;">
                    <div style="text-align: center; margin-bottom: 25px;">
                        <div style="display: inline-block; background-color: #fff5f5; padding: 15px; border-radius: 50%%;">
                            <span style="font-size: 24px;">🔒</span>
                        </div>
                        <h2 style="color: #e53e3e; margin-top: 15px;">Access Removed</h2>
                    </div>
                    <p>Hello,</p>
                    <p>We are writing to inform you that your access to the note <strong>"%s"</strong> has been revoked.</p>
                    <p style="color: #718096; line-height: 1.8;">You will no longer be able to view or edit this note. If you believe this happened in error, please contact the note's creator.</p>
                    %s
                </div>
            </body>
            </html>
            """.formatted(noteTitle, emailFooter);

        sendHtmlEmail(recipientEmail, subject, body);
    }

    private String buildRoleBadge(NoteAccessRole role) {
        String color = switch (role) {
            case VIEWER -> "#3182ce"; // Blue
            case EDITOR -> "#d69e2e"; // Gold
            case SUPER  -> "#e53e3e"; // Red
            default     -> "#718096"; // Gray
        };

        String description = switch (role) {
            case VIEWER -> "Read-only access. Stay in the loop with live updates.";
            case EDITOR -> "Collaborator access. Edit content in real-time with the team.";
            case SUPER  -> "Administrative access. Edit content and manage collaborator permissions.";
            default     -> "Collaborator access granted.";
        };

        return String.format(
                "<h2 style='margin: 8px 0 4px 0; color: %s; font-size: 20px; font-weight: 800;'>%s</h2>" +
                        "<p style='margin: 0; color: #4a5568; font-size: 14px; line-height: 1.4;'>%s</p>",
                color, role, description
        );
    }
}