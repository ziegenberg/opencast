module.exports = {
    "extends": "../../docs/checkstyle/eslintrc.js",
    "parser": "@babel/eslint-parser",
    "parserOptions": {        
        "sourceType": "module",
        "ecmaVersion": 2017
    },
    "globals": {
        "require": true,
    },
    "rules": {
        "no-unused-vars": "warn",
        "no-unsafe-optional-chaining": "warn",
        "no-alert": "warn",
        "no-console": "warn"
    }
};
