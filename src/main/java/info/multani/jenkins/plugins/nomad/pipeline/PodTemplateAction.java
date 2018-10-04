package info.multani.jenkins.plugins.nomad.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.BulkChange;
import hudson.model.Run;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import jenkins.model.RunAction2;

public class PodTemplateAction extends AbstractInvisibleRunAction2 implements RunAction2 {

    private static final Logger LOGGER = Logger.getLogger(PodTemplateAction.class.getName());

    PodTemplateAction() {
        super();
    }

    @Deprecated
    PodTemplateAction(Run run) {
        setRun(run);
    }

    protected static void push(@NonNull Run<?, ?> run, @NonNull String item) throws IOException {
        AbstractInvisibleRunAction2.push(run, PodTemplateAction.class, item);
    }

    @Deprecated
    public String pop() throws IOException {
        if (run == null) {
            LOGGER.warning("run is null, cannot pop");
            return null;
        }
        synchronized (run) {
            BulkChange bc = new BulkChange(run);
            try {
                PodTemplateAction action = run.getAction(PodTemplateAction.class);
                if (action == null) {
                    action = new PodTemplateAction(run);
                    run.addAction(action);
                }
                String template = action.stack.pop();
                bc.commit();
                return template;
            } finally {
                bc.abort();
                return null;
            }
        }
    }

    public List<String> getParentTemplateList() {
        return new ArrayList<>(stack);
    }

    public String getParentTemplates() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String template : getParentTemplateList()) {
            if (first) {
                first = false;
            } else {
                sb.append(" ");
            }
            sb.append(template);

        }
        return sb.toString();
    }

}
