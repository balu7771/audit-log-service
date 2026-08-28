package com.persistent.auditlog.config;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.type.SqlTypes;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class JsonbType implements UserType<String> {

    @Override
    public int getSqlType() {
        return SqlTypes.JSON;
    }

    @Override
    public Class<String> returnedClass() {
        return String.class;
    }

    @Override
    public boolean equals(String x, String y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null) {
            return false;
        }
        return x.equals(y);
    }

    @Override
    public int hashCode(String x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public String nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        return rs.getString(position);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, String value, int index, SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setObject(index, null);
        } else {
            st.setObject(index, value, java.sql.Types.OTHER);
        }
    }

    @Override
    public String deepCopy(String value) {
        if (value == null) {
            return null;
        }
        return new String(value);
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(String value) {
        return deepCopy(value);
    }

    @Override
    public String assemble(Serializable cached, Object owner) {
        return deepCopy((String) cached);
    }
}
