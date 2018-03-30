package info.multani.jenkins.plugins.nomad;

import com.hashicorp.nomad.javasdk.NomadApiClient;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.jenkinsci.plugins.durabletask.executors.Messages;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

/**
 * @author Carlos Sanchez carlos@apache.org
 */
public class NomadSlave extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(NomadSlave.class.getName());

    private static final Integer DISCONNECTION_TIMEOUT = Integer
            .getInteger(NomadSlave.class.getName() + ".disconnectionTimeout", 5);

    private static final long serialVersionUID = -8642936855413034232L;
    private static final String DEFAULT_AGENT_PREFIX = "jenkins-agent";

    /**
     * The resource bundle reference
     */
    private static final ResourceBundleHolder HOLDER = ResourceBundleHolder.get(Messages.class);

    private final String cloudName;
    private final String namespace;
    private final NomadJobTemplate template;

    public NomadJobTemplate getTemplate() {
        return template;
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public NomadSlave(NomadJobTemplate template, String nodeDescription, NomadCloud cloud, String labelStr)
            throws Descriptor.FormException, IOException {

        this(template, nodeDescription, cloud.name, labelStr, new OnceRetentionStrategy(cloud.getRetentionTimeout()));
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public NomadSlave(NomadJobTemplate template, String nodeDescription, NomadCloud cloud, Label label)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud.name, label.toString(), new OnceRetentionStrategy(cloud.getRetentionTimeout())) ;
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public NomadSlave(NomadJobTemplate template, String nodeDescription, NomadCloud cloud, String labelStr,
                           RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud.name, labelStr, rs);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @DataBoundConstructor // make stapler happy. Not actually used.
    public NomadSlave(NomadJobTemplate template, String nodeDescription, String cloudName, String labelStr,
                           RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(getSlaveName(template), template, nodeDescription, cloudName, labelStr, new NomadLauncher(), rs);
    }

    protected NomadSlave(String name, NomadJobTemplate template, String nodeDescription, String cloudName, String labelStr,
                           ComputerLauncher computerLauncher, RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        super(name,
                nodeDescription,
                template.getRemoteFs(),
                1,
//                template.getNodeUsageMode() != null ? template.getNodeUsageMode() : TODO
                        Node.Mode.NORMAL,
                labelStr == null ? null : labelStr,
                computerLauncher,
                rs,
                template.getNodeProperties()
                
        );

        this.cloudName = cloudName;
        this.namespace = Util.fixEmpty(template.getNamespace());
        this.template = template;
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getNamespace() {
        return namespace;
    }


    /**
     * Returns the cloud instance which created this agent.
     * @return the cloud instance which created this agent.
     * @throws IllegalStateException if the cloud doesn't exist anymore, or is not a {@link NomadCloud}.
     */
    @Nonnull
    public NomadCloud getNomadCloud() {
        Cloud cloud = Jenkins.getInstance().getCloud(getCloudName());
        if (cloud instanceof NomadCloud) {
            return (NomadCloud) cloud;
        } else {
            throw new IllegalStateException(getClass().getName() + " can be launched only by instances of " + NomadCloud.class.getName());
        }
    }

    static String getSlaveName(NomadJobTemplate template) {
        String randString = RandomStringUtils.random(5, "bcdfghjklmnpqrstvwxz0123456789");
        String name = template.getName();
        if (StringUtils.isEmpty(name)) {
            return String.format("%s-%s", DEFAULT_AGENT_PREFIX,  randString);
        }
        // no spaces
        name = name.replaceAll("[ _]", "-").toLowerCase();
        // keep it under 63 chars (62 is used to account for the '-')
        name = name.substring(0, Math.min(name.length(), 62 - randString.length()));
        return String.format("%s-%s", name, randString);
    }

    @Override
    public NomadComputer createComputer() {
        return new NomadComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "Terminating Kubernetes instance for agent {0}", name);

        Computer computer = toComputer();
        if (computer == null) {
            String msg = String.format("Computer for agent is null: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        OfflineCause offlineCause = OfflineCause.create(new Localizable(HOLDER, "offline"));

        Future<?> disconnected = computer.disconnect(offlineCause);
        // wait a bit for disconnection to avoid stack traces in logs
        try {
            disconnected.get(DISCONNECTION_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            String msg = String.format("Ignoring error waiting for agent disconnection %s: %s", name, e.getMessage());
            LOGGER.log(Level.INFO, msg, e);
        }

        if (getCloudName() == null) {
            String msg = String.format("Cloud name is not set for agent, can't terminate: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }
        NomadCloud cloud;
        try {
            cloud = getNomadCloud();
        } catch (IllegalStateException e) {
            e.printStackTrace(listener.fatalError("Unable to terminate agent. Cloud may have been removed. There may be leftover resources on the Kubernetes cluster."));
            LOGGER.log(Level.SEVERE, String.format("Unable to terminate agent %s. Cloud may have been removed. There may be leftover resources on the Kubernetes cluster.", name));
            return;
        }
        NomadApiClient client;
        try {
            client = cloud.connect();
        } catch (UnrecoverableKeyException | CertificateEncodingException | NoSuchAlgorithmException
                | KeyStoreException e) {
            String msg = String.format("Failed to connect to cloud %s", getCloudName());
            e.printStackTrace(listener.fatalError(msg));
            return;
        }

//        String actualNamespace = getNamespace() == null ? client.getNamespace() : getNamespace();
//        try {
//            Boolean deleted = client.pods().inNamespace(actualNamespace).withName(name).delete();
//            if (!Boolean.TRUE.equals(deleted)) {
//                String msg = String.format("Failed to delete pod for agent %s/%s: not found", actualNamespace, name);
//                LOGGER.log(Level.WARNING, msg);
//                listener.error(msg);
//                return;
//            }
//        } catch (NomadException e) {
//            String msg = String.format("Failed to delete pod for agent %s/%s: %s", actualNamespace, name,
//                    e.getMessage());
//            LOGGER.log(Level.WARNING, msg, e);
//            listener.error(msg);
//            return;
//        }

        String msg = String.format("Terminated Nomad job for agent %s", name);
        LOGGER.log(Level.INFO, msg);
        listener.getLogger().println(msg);
        LOGGER.log(Level.INFO, "Disconnected computer {0}", name);
    }

    @Override
    public String toString() {
        return String.format("KubernetesSlave name: %s", name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NomadSlave that = (NomadSlave) o;

        if (cloudName != null ? !cloudName.equals(that.cloudName) : that.cloudName != null) return false;
        if (namespace != null ? !namespace.equals(that.namespace) : that.namespace != null) return false;
        return template != null ? template.equals(that.template) : that.template == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (cloudName != null ? cloudName.hashCode() : 0);
        result = 31 * result + (namespace != null ? namespace.hashCode() : 0);
        result = 31 * result + (template != null ? template.hashCode() : 0);
        return result;
    }

    /**
     * Returns a new {@link Builder} instance.
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a {@link NomadSlave} instance.
     */
    public static class Builder {
        private String name;
        private String nodeDescription;
        private NomadJobTemplate podTemplate;
        private NomadCloud cloud;
        private String label;
        private ComputerLauncher computerLauncher;
        private RetentionStrategy retentionStrategy;

        /**
         * @param name The name of the future {@link NomadSlave}
         * @return the current instance for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * @param nodeDescription The node description of the future {@link NomadSlave}
         * @return the current instance for method chaining
         */
        public Builder nodeDescription(String nodeDescription) {
            this.nodeDescription = nodeDescription;
            return this;
        }

        /**
         * @param podTemplate The pod template the future {@link NomadSlave} has been created from
         * @return the current instance for method chaining
         */
        public Builder podTemplate(NomadJobTemplate podTemplate) {
            this.podTemplate = podTemplate;
            return this;
        }

        /**
         * @param cloud The cloud that is provisioning the {@link NomadSlave} instance.
         * @return the current instance for method chaining
         */
        public Builder cloud(NomadCloud cloud) {
            this.cloud = cloud;
            return this;
        }

        /**
         * @param label The label the {@link NomadSlave} has.
         * @return the current instance for method chaining
         */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /**
         * @param computerLauncher The computer launcher to use to launch the {@link NomadSlave} instance.
         * @return the current instance for method chaining
         */
        public Builder computerLauncher(ComputerLauncher computerLauncher) {
            this.computerLauncher = computerLauncher;
            return this;
        }

        /**
         * @param retentionStrategy The retention strategy to use for the {@link NomadSlave} instance.
         * @return the current instance for method chaining
         */
        public Builder retentionStrategy(RetentionStrategy retentionStrategy) {
            this.retentionStrategy = retentionStrategy;
            return this;
        }

        private RetentionStrategy determineRetentionStrategy() {
            if (podTemplate.getIdleMinutes() == 0) {
                return new OnceRetentionStrategy(cloud.getRetentionTimeout());
            } else {
                return new CloudRetentionStrategy(podTemplate.getIdleMinutes());
            }
        }

        /**
         * Builds the resulting {@link NomadSlave} instance.
         * @return an initialized {@link NomadSlave} instance.
         * @throws IOException
         * @throws Descriptor.FormException
         */
        public NomadSlave build() throws IOException, Descriptor.FormException {
            Validate.notNull(podTemplate);
            Validate.notNull(cloud);
            return new NomadSlave(
                    name == null ? getSlaveName(podTemplate) : name,
                    podTemplate,
                    nodeDescription == null ? podTemplate.getName() : nodeDescription,
                    cloud.name,
                    label == null ? podTemplate.getLabel() : label,
                    computerLauncher == null ? new NomadLauncher() : computerLauncher,
                    retentionStrategy == null ? determineRetentionStrategy() : retentionStrategy);
        }
    }


    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Nomad Agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

    }
}
