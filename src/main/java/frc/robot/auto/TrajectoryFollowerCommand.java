// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.auto;

import static edu.wpi.first.wpilibj.util.ErrorMessages.requireNonNullParam;

import edu.wpi.first.wpilibj.Timer;
import frc.robot.controllers.PIDController;
import frc.robot.controllers.ProfiledPIDController;
import edu.wpi.first.wpilibj.controller.SimpleMotorFeedforward;
import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.kinematics.MecanumDriveMotorVoltages;
import edu.wpi.first.wpilibj.kinematics.MecanumDriveWheelSpeeds;
import edu.wpi.first.wpilibj.trajectory.Trajectory;
import edu.wpi.first.wpilibj2.command.CommandBase;
import edu.wpi.first.wpilibj2.command.Subsystem;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A command that uses two PID controllers ({@link PIDController}) and a ProfiledPIDController
 * ({@link ProfiledPIDController}) to follow a trajectory {@link Trajectory} with a mecanum drive.
 *
 * <p>The command handles trajectory-following, Velocity PID calculations, and feedforwards
 * internally. This is intended to be a more-or-less "complete solution" that can be used by teams
 * without a great deal of controls expertise.
 *
 * <p>Advanced teams seeking more flexibility (for example, those who wish to use the onboard PID
 * functionality of a "smart" motor controller) may use the secondary constructor that omits the PID
 * and feedforward functionality, returning only the raw wheel speeds from the PID controllers.
 *
 * <p>The robot angle controller does not follow the angle given by the trajectory but rather goes
 * to the angle given in the final state of the trajectory.
 */
@SuppressWarnings({"PMD.TooManyFields", "MemberName"})
public class TrajectoryFollowerCommand extends CommandBase {
  private final Timer m_timer = new Timer();
  private final boolean m_usePID;
  private final Trajectory m_trajectory;
  private final Trajectory m_headingTrajectory;
  private final Supplier<Pose2d> m_pose;
  private final SimpleMotorFeedforward m_feedforward;
  private final MecanumKinematics m_kinematics;
  private final HolonomicController m_controller;
  private final double m_maxWheelVelocityMetersPerSecond;
  private final PIDController m_frontLeftController;
  private final PIDController m_rearLeftController;
  private final PIDController m_frontRightController;
  private final PIDController m_rearRightController;
  private final Supplier<MecanumDriveWheelSpeeds> m_currentWheelSpeeds;
  private final Consumer<MecanumDriveMotorVoltages> m_outputDriveVoltages;
  private final Consumer<MecanumDriveWheelSpeeds> m_outputWheelSpeeds;
  private MecanumDriveWheelSpeeds m_prevSpeeds;
  private double m_prevTime;

  /**
   * Constructs a new MecanumControllerCommand that when executed will follow the provided
   * trajectory. PID control and feedforward are handled internally. Outputs are scaled from -12 to
   * 12 as a voltage output to the motor.
   *
   * <p>Note: The controllers will *not* set the outputVolts to zero upon completion of the path
   * this is left to the user, since it is not appropriate for paths with nonstationary endstates.
   * 
   * <p>Note: This follower *DOES* follow the trajectories heading at each point. Ensure you are sure of the heading at each point!
   * This differs from the MecanumControllerCommand which decouples the heading from the trajectory! 
   *
   * @param trajectory The trajectory to follow.
   * @param headTrajectory The heading trajectory to follow.
   * @param pose A function that supplies the robot pose - use one of the odometry classes to
   *     provide this.
   * @param feedforward The feedforward to use for the drivetrain.
   * @param kinematics The kinematics for the robot drivetrain.
   * @param xController The Trajectory Tracker PID controller for the robot's x position.
   * @param yController The Trajectory Tracker PID controller for the robot's y position.
   * @param thetaController The Trajectory Tracker PID controller for angle for the robot.
   * @param maxWheelVelocityMetersPerSecond The maximum velocity of a drivetrain wheel.
   * @param frontLeftController The front left wheel velocity PID.
   * @param rearLeftController The rear left wheel velocity PID.
   * @param frontRightController The front right wheel velocity PID.
   * @param rearRightController The rear right wheel velocity PID.
   * @param currentWheelSpeeds A MecanumDriveWheelSpeeds object containing the current wheel speeds.
   * @param outputDriveVoltages A MecanumDriveMotorVoltages object containing the output motor
   *     voltages.
   * @param requirements The subsystems to require.
   */
  @SuppressWarnings({"PMD.ExcessiveParameterList", "ParameterName"})
  public TrajectoryFollowerCommand(
      Trajectory trajectory,
      Trajectory headTrajectory,
      Supplier<Pose2d> pose,
      SimpleMotorFeedforward feedforward,
      MecanumKinematics kinematics,
      PIDController xController,
      PIDController yController,
      ProfiledPIDController thetaController,
      double maxWheelVelocityMetersPerSecond,
      PIDController frontLeftController,
      PIDController rearLeftController,
      PIDController frontRightController,
      PIDController rearRightController,
      Supplier<MecanumDriveWheelSpeeds> currentWheelSpeeds,
      Consumer<MecanumDriveMotorVoltages> outputDriveVoltages,
      Subsystem... requirements) {
    m_trajectory = requireNonNullParam(trajectory, "trajectory", "MecanumControllerCommand");
    m_headingTrajectory = requireNonNullParam(headTrajectory, "headTrajectory", "MecanumControllerCommand");
    m_pose = requireNonNullParam(pose, "pose", "MecanumControllerCommand");
    m_feedforward = requireNonNullParam(feedforward, "feedforward", "MecanumControllerCommand");
    m_kinematics = requireNonNullParam(kinematics, "kinematics", "MecanumControllerCommand");

    m_controller =
        new HolonomicController(
            requireNonNullParam(xController, "xController", "SwerveControllerCommand"),
            requireNonNullParam(yController, "xController", "SwerveControllerCommand"),
            requireNonNullParam(thetaController, "thetaController", "SwerveControllerCommand"));

    m_maxWheelVelocityMetersPerSecond =
        requireNonNullParam(
            maxWheelVelocityMetersPerSecond,
            "maxWheelVelocityMetersPerSecond",
            "MecanumControllerCommand");

    m_frontLeftController =
        requireNonNullParam(frontLeftController, "frontLeftController", "MecanumControllerCommand");
    m_rearLeftController =
        requireNonNullParam(rearLeftController, "rearLeftController", "MecanumControllerCommand");
    m_frontRightController =
        requireNonNullParam(
            frontRightController, "frontRightController", "MecanumControllerCommand");
    m_rearRightController =
        requireNonNullParam(rearRightController, "rearRightController", "MecanumControllerCommand");

    m_currentWheelSpeeds =
        requireNonNullParam(currentWheelSpeeds, "currentWheelSpeeds", "MecanumControllerCommand");

    m_outputDriveVoltages =
        requireNonNullParam(outputDriveVoltages, "outputDriveVoltages", "MecanumControllerCommand");

    m_outputWheelSpeeds = null;

    m_usePID = true;

    addRequirements(requirements);
  }

  @Override
  public void initialize() {
    var initialState = m_trajectory.sample(0);
    var initialHeadingState = m_headingTrajectory.sample(0);

    var initialXVelocity =
        initialState.velocityMetersPerSecond * initialHeadingState.poseMeters.getRotation().getCos();
    var initialYVelocity =
        initialState.velocityMetersPerSecond * initialHeadingState.poseMeters.getRotation().getSin();

    m_prevSpeeds =
        m_kinematics.toWheelSpeeds(new ChassisSpeeds(initialXVelocity, initialYVelocity, 0.0));

        System.out.println("=============NEW==============");
    m_timer.reset();
    m_timer.start();
  }

  @Override
  @SuppressWarnings("LocalVariableName")
  public void execute() {
    double curTime = m_timer.get();
    double dt = curTime - m_prevTime;

    var desiredState = m_trajectory.sample(curTime);
    var desiredHeading = m_headingTrajectory.sample(curTime);

    var targetChassisSpeeds =
        m_controller.calculate(m_pose.get(), desiredState, desiredState.poseMeters.getRotation(), desiredHeading);
    var targetWheelSpeeds = m_kinematics.toWheelSpeeds(targetChassisSpeeds);

    targetWheelSpeeds.desaturate(m_maxWheelVelocityMetersPerSecond);

    var frontLeftSpeedSetpoint = targetWheelSpeeds.frontLeftMetersPerSecond;
    var rearLeftSpeedSetpoint = targetWheelSpeeds.rearLeftMetersPerSecond;
    var frontRightSpeedSetpoint = targetWheelSpeeds.frontRightMetersPerSecond;
    var rearRightSpeedSetpoint = targetWheelSpeeds.rearRightMetersPerSecond;

    double frontLeftOutput;
    double rearLeftOutput;
    double frontRightOutput;
    double rearRightOutput;

    if (m_usePID) {
      final double frontLeftFeedforward =
          m_feedforward.calculate(
              frontLeftSpeedSetpoint,
              (frontLeftSpeedSetpoint - m_prevSpeeds.frontLeftMetersPerSecond) / dt);

      final double rearLeftFeedforward =
          m_feedforward.calculate(
              rearLeftSpeedSetpoint,
              (rearLeftSpeedSetpoint - m_prevSpeeds.rearLeftMetersPerSecond) / dt);

      final double frontRightFeedforward =
          m_feedforward.calculate(
              frontRightSpeedSetpoint,
              (frontRightSpeedSetpoint - m_prevSpeeds.frontRightMetersPerSecond) / dt);

      final double rearRightFeedforward =
          m_feedforward.calculate(
              rearRightSpeedSetpoint,
              (rearRightSpeedSetpoint - m_prevSpeeds.rearRightMetersPerSecond) / dt);

      frontLeftOutput =
          frontLeftFeedforward
              + m_frontLeftController.calculate(
                  m_currentWheelSpeeds.get().frontLeftMetersPerSecond, frontLeftSpeedSetpoint);

      rearLeftOutput =
          rearLeftFeedforward
              + m_rearLeftController.calculate(
                  m_currentWheelSpeeds.get().rearLeftMetersPerSecond, rearLeftSpeedSetpoint);

      frontRightOutput =
          frontRightFeedforward
              + m_frontRightController.calculate(
                  m_currentWheelSpeeds.get().frontRightMetersPerSecond, frontRightSpeedSetpoint);

      rearRightOutput =
          rearRightFeedforward
              + m_rearRightController.calculate(
                  m_currentWheelSpeeds.get().rearRightMetersPerSecond, rearRightSpeedSetpoint);

      m_outputDriveVoltages.accept(
          new MecanumDriveMotorVoltages(
              frontLeftOutput, frontRightOutput, rearLeftOutput, rearRightOutput));

    } else {
      m_outputWheelSpeeds.accept(
          new MecanumDriveWheelSpeeds(
              frontLeftSpeedSetpoint,
              frontRightSpeedSetpoint,
              rearLeftSpeedSetpoint,
              rearRightSpeedSetpoint));
    }

    m_prevTime = curTime;
    m_prevSpeeds = targetWheelSpeeds;
  }

  @Override
  public void end(boolean interrupted) {
    m_timer.stop();
  }

  @Override
  public boolean isFinished() {
    return m_timer.hasElapsed(m_trajectory.getTotalTimeSeconds());
  }
}