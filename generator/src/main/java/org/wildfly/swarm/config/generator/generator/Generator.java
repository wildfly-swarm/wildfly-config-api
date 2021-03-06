package org.wildfly.swarm.config.generator.generator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.forge.roaster.model.JavaType;
import org.jboss.logmanager.Level;
import org.wildfly.swarm.config.generator.model.DefaultStatementContext;
import org.wildfly.swarm.config.generator.model.ResourceDescription;
import org.wildfly.swarm.config.generator.operations.ReadDescription;
import org.wildfly.swarm.config.runtime.model.AddressTemplate;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADDRESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_TYPES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;

/**
 * @author Heiko Braun
 * @since 29/07/15
 */
public class Generator {

    private static final Logger log = Logger.getLogger(Generator.class.getName());

    private final ModelControllerClient client;

    private final DefaultStatementContext statementContext;

    private final Path targetDir;

    private final Config config;

    private final String artifact;

    public Generator(String targetDir, Config config, String artifact) throws Exception {
        this.client = ClientFactory.createClient(config);
        this.statementContext = new DefaultStatementContext();
        this.targetDir = Paths.get(targetDir);
        this.config = config;
        this.artifact = artifact;
    }

    public static void main(String[] args) throws Exception {
        log.info("Config: " + args[0]);
        log.info("Output: " + args[1]);
        log.info("Artifact: " + args[2]);

        Config config = Config.fromJson(args[0]);
        Generator generator = new Generator(args[1], config, args[2]);
        try {
            generator.processGeneratorTargets();
        } finally {
            generator.shutdown();
        }
    }

    public void deleteDir(Path directory) throws Exception {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

        });
    }

    public void shutdown() {
        try {
            client.close();
        } catch (IOException e) {
            log.log(Level.ERROR, e.getMessage());
        }
    }

    public void processGeneratorTargets() throws Exception {
        if (Files.exists(targetDir)) {
            log.info("Delete output dir: " + targetDir);
            deleteDir(targetDir);
        }

        List<SubsystemPlan> subsystems = new ArrayList<>();

        List<SourceFactory> factories = Arrays.asList(
            new ResourceFactory(),
            new ConsumerFactory(),
            new SupplierFactory()
        );

        for (GeneratorTarget target : config.getGeneratorTargets()) {
            // load resource entry point recursively
            ResourceMetaData resourceMetaData = loadResourceMetaData(target);

            // generate classes

            SubsystemPlan plan = new SubsystemPlan(resourceMetaData);
            subsystems.add(plan);

            for (EnumPlan enumPlan : plan.getEnumPlans()) {
                EnumFactory factory = new EnumFactory();
                JavaType javaType = factory.create(plan, enumPlan);
                write(javaType);
            }

            List<ClassPlan> classPlans = plan.getClassPlans();
            for (ClassPlan classPlan : classPlans) {
                for (SourceFactory factory : factories) {
                    classPlan.addSource(factory.create(plan, classPlan));
                }
            }

            for (ClassPlan classPlan : classPlans) {
                for (JavaType javaType : classPlan.getSources()) {
                    write(javaType);
                }
            }
        }

        log.info("TARGET DIR: " + this.targetDir);

        generateMainModuleXml(subsystems);
        generateApiModuleXml();
        generateMarker();
    }

    private void generateMainModuleXml(List<SubsystemPlan> subsystems) throws IOException {
        String moduleName = this.config.getModuleName();

        Path moduleXml = this.targetDir.resolve(Paths.get("..", "classes", "modules")).resolve(this.config.getModulePath("main")).toAbsolutePath();
        log.info("** GENERATE MAIN MODULE.XML: " + moduleXml);
        Files.createDirectories(moduleXml.getParent());
        try (PrintWriter out = new PrintWriter(new FileOutputStream(moduleXml.toFile()))) {
            out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "\n" +
                    "<module xmlns=\"urn:jboss:module:1.3\" name=\"" + moduleName + "\">\n" +
                    "  <dependencies>\n" +
                    "    <!-- For when run with bonafide IDE classpath -->\n" +
                    "    <system export=\"true\">\n" +
                    "      <paths>");

            subsystems.stream()
                    .flatMap(e -> e.getClassPlans().stream())
                    .map(ClassPlan::getPackageName)
                    .distinct()
                    .sorted()
                    .forEach(e -> {
                        out.println("        <path name=\"" + e.replace('.', '/') + "\"/>");
                    });

            out.println("      </paths>\n" +
                    "    </system>\n" +
                    "    <module name=\"" + moduleName + "\" slot=\"api\" export=\"true\" services=\"export\"/>\n" +
                    "    <module name=\"org.wildfly.swarm.configuration.runtime\" export=\"true\"/>\n" +
                    "  </dependencies>\n" +
                    "\n" +
                    "</module>");
        }
    }

    private void generateApiModuleXml() throws IOException {
        String moduleName = this.config.getModuleName();

        Path moduleXml = this.targetDir.resolve(Paths.get("..", "classes", "modules")).resolve(this.config.getModulePath("api")).toAbsolutePath();
        log.info("** GENERATE API MODULE.XML: " + moduleXml);

        Files.createDirectories(moduleXml.getParent());
        try (PrintWriter out = new PrintWriter(new FileOutputStream(moduleXml.toFile()))) {
            out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "\n" +
                    "<module xmlns=\"urn:jboss:module:1.3\" name=\"" + moduleName + "\" slot=\"api\">\n" +
                    "  <resources>\n" +
                    "    <artifact name=\"" + this.artifact + "\"/>\n" +
                    "  </resources>\n" +
                    "  <dependencies>\n" +
                    "    <module name=\"org.wildfly.swarm.configuration.runtime\" export=\"true\"/>\n" +
                    "  </dependencies>\n" +
                    "\n" +
                    "</module>");
        }
    }

    private void generateMarker() throws IOException {
        Path confPath = this.targetDir.resolve(Paths.get("..", "classes", "wildfly-swarm-modules.conf"));

        Files.createDirectories(confPath.getParent());
        try (PrintWriter out = new PrintWriter(new FileOutputStream(confPath.toFile()))) {
            out.println();
        }
    }

    private void write(JavaType javaClass) throws IOException {
        String dir = this.targetDir + File.separator + javaClass.getPackage().replace(".", File.separator);
        Files.createDirectories(Paths.get(dir));

        Path fileName = Paths.get(dir + File.separator + javaClass.getName() + ".java");
        if (Files.exists(fileName)) {
            log.warning("File already exists, will be replaced: " + fileName);
        }

        Files.write(fileName, javaClass.toString().getBytes());
    }

    private ResourceMetaData loadResourceMetaData(GeneratorTarget generatorTarget) throws Exception {
        AddressTemplate address = generatorTarget.getSourceAddress();

        ModelNode composite = new ModelNode();
        composite.get(OP).set(COMPOSITE);
        composite.get(ADDRESS).setEmptyList();

        List<ModelNode> steps = new ArrayList<>();

        // read parent child type
        Integer tokens = address.tokenLength();
        AddressTemplate parentAddress = address.subTemplate(0, tokens - 1);
        ModelNode childTypes = new ModelNode();
        childTypes.get(OP).set(READ_CHILDREN_TYPES_OPERATION);
        childTypes.get(ADDRESS).set(parentAddress.resolve(new DefaultStatementContext()));
        childTypes.get("include-singletons").set(true);
        steps.add(childTypes);

        // read resource description
        ReadDescription rrd = new ReadDescription(address);
        steps.add(rrd.resolve(this.statementContext));

        composite.get(STEPS).set(steps);
        ModelNode response = client.execute(composite);

        // parent type
        boolean isSingleton = false;
        List<ModelNode> types = response.get(RESULT).get("step-1").get(RESULT).asList();
        for (ModelNode type : types) {
            if (type.asString().equals(address.getResourceType() + "=" + address.getResourceName())) {
                isSingleton = true;
                break;
            }
        }

        // resource meta data
        ResourceDescription description = ResourceDescription.from(response.get(RESULT).get("step-2"));
        if (isSingleton) {
            description.setSingletonName(address.getResourceName());
        }

        return new ResourceMetaData(generatorTarget.getSourceAddress(), description);
    }

}
