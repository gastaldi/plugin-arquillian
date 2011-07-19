package org.jboss.seam.forge.arquillian;

import org.jboss.forge.parser.JavaParser;
import org.jboss.forge.parser.java.JavaClass;
import org.jboss.forge.parser.java.JavaSource;
import org.jboss.forge.project.Project;
import org.jboss.forge.project.dependencies.Dependency;
import org.jboss.forge.project.dependencies.DependencyBuilder;
import org.jboss.forge.project.dependencies.ScopeType;
import org.jboss.forge.project.facets.DependencyFacet;
import org.jboss.forge.project.facets.JavaExecutionFacet;
import org.jboss.forge.project.facets.JavaSourceFacet;
import org.jboss.forge.resources.java.JavaResource;
import org.jboss.forge.shell.PromptType;
import org.jboss.forge.shell.Shell;
import org.jboss.forge.shell.events.PickupResource;
import org.jboss.forge.shell.plugins.*;
import org.jboss.forge.shell.util.BeanManagerUtils;
import org.jboss.seam.forge.arquillian.container.Container;
import org.jboss.seam.render.TemplateCompiler;
import org.jboss.seam.render.template.CompiledTemplateResource;
import org.jboss.seam.render.template.resolver.TemplateResolverFactory;
import org.mvel2.integration.VariableResolverFactory;

import javax.enterprise.event.Event;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;

@Alias("arquillian")
@RequiresFacet(JavaSourceFacet.class)
@Help("A plugin that helps setting up Arquillian tests")
public class ArquillianPlugin implements Plugin
{
   @Inject
   private Project project;

   @Inject
   BeanManager beanManager;

   @Inject
   private Event<PickupResource> pickup;

   @Inject
   @Current
   private JavaResource resource;

   @Inject
   private Shell shell;

   @Inject
   private TemplateCompiler compiler;

   @Inject
   private VariableResolverFactory variableFactory;

   @Inject
   private TemplateResolverFactory resolverFactory;


   private String arquillianVersion;

   private DependencyFacet dependencyFacet;

   @Command(value = "setup", help = "Add a container profile to the maven configuration. Multiple containers can exist on a single project.")
   public void setup(@Option(name = "test-framework", defaultValue = "junit", required = false) String testFramework,
                     @Option(name = "container", required = true) ArquillianContainer container,
                     final PipeOut out)
   {

      dependencyFacet = project.getFacet(DependencyFacet.class);

      if (testFramework.equals("testng"))
      {
         installTestNgDependencies();
      } else
      {
         installJunitDependencies();
      }

      Container contextualInstance = BeanManagerUtils.getContextualInstance(beanManager, container.getContainer());
      contextualInstance.installDependencies(arquillianVersion);
   }


   @Command(value = "create-test", help = "Create a new test class with a default @Deployment method")
   public void createTest(
           @Option(name = "class", required = true, type = PromptType.JAVA_CLASS) JavaResource classUnderTest,
           @Option(name = "enableJPA", required = false, flagOnly = true) boolean enableJPA,
           final PipeOut out) throws FileNotFoundException
   {
      JavaSourceFacet java = project.getFacet(JavaSourceFacet.class);

      InputStream template = this.getClass().getResourceAsStream("TemplateTest.jv");
      JavaSource<?> javaSource = classUnderTest.getJavaSource();

      CompiledTemplateResource backingBeanTemplate = compiler.compileResource(template);
      HashMap<Object, Object> context = new HashMap<Object, Object>();
      context.put("package", javaSource.getPackage());
      context.put("ClassToTest", javaSource.getName());
      context.put("classToTest", javaSource.getName().toLowerCase());
      context.put("packageImport", javaSource.getPackage());
      context.put("enableJPA", enableJPA);

      JavaClass testClass = JavaParser.parse(JavaClass.class, backingBeanTemplate.render(context));
      java.saveTestJavaSource(testClass);

      pickup.fire(new PickupResource(java.getTestJavaResource(testClass)));
   }

   /**
    * This command exports an Archive generated by a @Deployment method to disk. Because the project's classpath is not
    * in the classpath of Forge, the @Deployment method can't be called directly.The plugin works in the following
    * steps: 1 - Generate a new class to the src/test/java folder 2 - Compile the user's classes using mvn test-compile
    * 3 - Run the generated class using mvn exec:java (so that the project's classes are on the classpath) 4 - Delete
    * the generated class
    */
   @Command(value = "export", help = "Export a @Deployment configuration to a zip file on disk.")
   @RequiresResource(JavaResource.class)
   public void exportDeployment(@Option(name = "keepExporter", flagOnly = true) boolean keepExporter, PipeOut out)
   {

      JavaSourceFacet java = project.getFacet(JavaSourceFacet.class);
      try
      {
         JavaResource testJavaResource = java.getTestJavaResource("forge/arquillian/DeploymentExporter.java");
         if (!testJavaResource.exists())
         {
            generateExporterClass(java);
         }

         runExporterClass(out);

         if (!keepExporter)
         {
            testJavaResource.delete();
         }
      } catch (Exception ex)
      {
         throw new RuntimeException("Error while calling generated DeploymentExporter ", ex);
      }
   }

   private void runExporterClass(PipeOut out) throws IOException
   {
      JavaExecutionFacet facet = project.getFacet(JavaExecutionFacet.class);
      facet.executeProjectClass("forge.arquillian.DeploymentExporter", resource.getJavaSource().getQualifiedName());
   }

   private void generateExporterClass(JavaSourceFacet java) throws FileNotFoundException
   {

      InputStream template = this.getClass().getResourceAsStream("DeploymentExporter.jv");

      CompiledTemplateResource deploymentExporterTemplate = compiler.compileResource(template);
      HashMap<Object, Object> context = new HashMap<Object, Object>();

      JavaClass deploymentExporter = JavaParser.parse(JavaClass.class, deploymentExporterTemplate.render(context));

      java.saveTestJavaSource(deploymentExporter);
   }

   private void installJunitDependencies()
   {
      DependencyBuilder junitDependency = createJunitDependency();
      if (!dependencyFacet.hasDependency(junitDependency))
      {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(junitDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of JUnit do you want to install?", dependencies);
         dependencyFacet.addDependency(dependency);
      }

      DependencyBuilder junitArquillianDependency = createJunitArquillianDependency();
      if (!dependencyFacet.hasDependency(junitArquillianDependency))
      {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(junitArquillianDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of Arquillian do you want to install?", dependencies, dependencies.get(dependencies.size() - 1));
         arquillianVersion = dependency.getVersion();
         dependencyFacet.addDependency(dependency);
      } else
      {
         arquillianVersion = dependencyFacet.getDependency(junitArquillianDependency).getVersion();
      }
   }

   private void installTestNgDependencies()
   {
      DependencyBuilder testngDependency = createTestNgDependency();
      if (!dependencyFacet.hasDependency(testngDependency))
      {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(testngDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of TestNG do you want to install?", dependencies);
         dependencyFacet.addDependency(dependency);
      }

      DependencyBuilder testNgArquillianDependency = createTestNgArquillianDependency();
      if (!dependencyFacet.hasDependency(testNgArquillianDependency))
      {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(testNgArquillianDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of Arquillian do you want to install?", dependencies, dependencies.get(dependencies.size() - 1));
         arquillianVersion = dependency.getVersion();
         dependencyFacet.addDependency(dependency);
      } else
      {
         arquillianVersion = dependencyFacet.getDependency(testNgArquillianDependency).getVersion();
      }
   }

   private DependencyBuilder createJunitDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("junit")
              .setArtifactId("junit")
              .setScopeType(ScopeType.TEST);
   }

   private DependencyBuilder createJunitArquillianDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("org.jboss.arquillian.junit")
              .setArtifactId("arquillian-junit-container")
              .setScopeType(ScopeType.TEST);
   }

   private DependencyBuilder createTestNgDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("org.testng")
              .setArtifactId("testng")
              .setScopeType(ScopeType.TEST);
   }

   private DependencyBuilder createTestNgArquillianDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("org.jboss.arquillian.testng")
              .setArtifactId("arquillian-testng-container")
              .setVersion(arquillianVersion);
   }
}
