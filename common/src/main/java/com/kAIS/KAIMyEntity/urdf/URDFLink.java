package com.kAIS.KAIMyEntity.urdf;

import org.joml.Vector3f;
import org.joml.Quaternionf;

public class URDFLink {
    public String name;
    public Visual visual;
    public Collision collision;
    public Inertial inertial;
    
    public URDFLink(String name) {
        this.name = name;
    }
    
    public static class Visual {
        public Geometry geometry;
        public Origin origin;
        public Material material;
        
        public Visual() {
            this.origin = new Origin();
        }
    }
    
    public static class Collision {
        public Geometry geometry;
        public Origin origin;
        
        public Collision() {
            this.origin = new Origin();
        }
    }
    
    public static class Inertial {
        public Origin origin;
        public Mass mass;
        public Inertia inertia;
        
        public Inertial() {
            this.origin = new Origin();
        }
        
        public static class Mass {
            public float value;
        }
        
        public static class Inertia {
            public float ixx, ixy, ixz;
            public float iyy, iyz, izz;
        }
    }
    
    public static class Geometry {
        public GeometryType type;
        public String meshFilename;
        public Vector3f scale;
        
        // For primitive shapes
        public Vector3f boxSize;
        public float cylinderRadius;
        public float cylinderLength;
        public float sphereRadius;
        
        public enum GeometryType {
            MESH, BOX, CYLINDER, SPHERE
        }
        
        public Geometry() {
            this.scale = new Vector3f(1.0f, 1.0f, 1.0f);
        }
    }
    
    public static class Origin {
        public Vector3f xyz;
        public Vector3f rpy; // roll, pitch, yaw
        
        public Origin() {
            this.xyz = new Vector3f(0.0f, 0.0f, 0.0f);
            this.rpy = new Vector3f(0.0f, 0.0f, 0.0f);
        }
        
        public Quaternionf getQuaternion() {
            // RPY (Roll-Pitch-Yaw) to Quaternion conversion
            Quaternionf qx = new Quaternionf().rotateX(rpy.x);
            Quaternionf qy = new Quaternionf().rotateY(rpy.y);
            Quaternionf qz = new Quaternionf().rotateZ(rpy.z);
            
            // ZYX order (typical for RPY)
            return qz.mul(qy).mul(qx);
        }
    }
    
    public static class Material {
        public String name;
        public Vector4f color; // RGBA
        public String textureFilename;
        
        public static class Vector4f {
            public float x, y, z, w;
            
            public Vector4f(float x, float y, float z, float w) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.w = w;
            }
        }
    }
}
